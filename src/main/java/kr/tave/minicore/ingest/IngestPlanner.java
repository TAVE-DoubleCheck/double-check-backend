package kr.tave.minicore.ingest;

import kr.tave.minicore.ingest.ApiModels.CoverageLine;
import kr.tave.minicore.ingest.ApiModels.PremiumItem;
import kr.tave.minicore.ingest.ApiModels.TermsItem;
import kr.tave.minicore.ingest.CoverageMerger.Paragraph;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 세 API의 원본을 받아 "DB에 쓸 행 목록(WritePlan)"을 만든다. DB·HTTP에 의존하지 않는 순수 로직.
 *
 * 상품 식별 (세 API에 공통 코드가 없음)
 *   약관 GDS_NM  ==  보험료 GDS_NM  → 상품코드(GDS_CD_S10) 확보
 *   약관 URL의 p 값  → 약관코드 (상품코드에서 계산하지 않음)
 *   보장 INSU_NM  →  설정의 수작업 매핑(coverage-name-map)
 *
 * 실패(예외) 조건: 정합성이 깨질 수 있는 상황은 조용히 넘기지 않는다.
 *   - 약관 상품인데 보험료 데이터가 없음
 *   - 같은 상품명에 서로 다른 상품코드, 같은 상품에 서로 다른 약관코드
 *   - 같은 요율 키가 두 번 등장
 *   - 적재 기준일에 유효한 버전이 없음, 보장의 적용시작일과 일치하는 버전이 없음
 *
 * 제외(리포트에만 기록): 약관 목록에 없는 상품(예: (무)하나로OK), 주계약이 아닌 행, 매핑 없는 보장 상품
 */
public final class IngestPlanner {

    public static final String MAIN_CONTRACT = "주계약";

    public record ProductRow(String productCode, String shortName, String fullName, String termsCode) {
    }

    public record VersionRow(String productCode, LocalDate effectiveFrom, LocalDate effectiveTo, String termsUrl) {
    }

    public record RateRow(String productCode, LocalDate versionFrom, int joinAge, String sex,
                          int payTermYears, boolean fullTermPay, String insureTermCode,
                          long premiumWon, long unitAmountWon) {
        String key() {
            return productCode + "/" + versionFrom + "/" + joinAge + "/" + sex + "/" + payTermYears + "/" + insureTermCode;
        }
    }

    public record CoverageRow(String productCode, LocalDate versionFrom, int sourceLineNo,
                              String benefitName, String text, boolean note) {
    }

    public record Report(Set<String> excludedPremiumProducts, int skippedNonMainContractRows,
                         Set<String> unmappedCoverageProducts) {
    }

    public record WritePlan(List<ProductRow> products, List<VersionRow> versions,
                            List<RateRow> rates, List<CoverageRow> coverages, Report report) {
    }

    private IngestPlanner() {
    }

    /**
     * @param businessDate   요율을 붙일 버전을 고르는 기준일(system_clock). 보험료 API에는 적용기간이 없으므로
     *                       "적재 시점에 유효한 버전의 요율"로 본다. (가정 — README 명시)
     * @param coverageNameMap 상품코드 → 보장 API 정식 상품명
     */
    public static WritePlan plan(List<TermsItem> terms, List<PremiumItem> premiums, List<CoverageLine> coverageLines,
                                 Map<String, String> coverageNameMap, LocalDate businessDate) {

        // 1. 보험료 응답에서 상품명 → 상품코드 (적재 범위 = 약관 목록에 있는 상품만 검사)
        //    범위 밖 상품은 같은 이름에 여러 코드가 있어도 된다. 실제 데이터: (무)하나로OK → 4102901, 4103001 …
        Set<String> termsNames = new TreeSet<>();
        terms.forEach(t -> termsNames.add(t.gdsNm().strip()));
        Map<String, String> codeByName = new HashMap<>();
        for (PremiumItem p : premiums) {
            if (!termsNames.contains(p.gdsNm().strip())) {
                continue;
            }
            String prev = codeByName.putIfAbsent(p.gdsNm().strip(), p.gdsCd().strip());
            if (prev != null && !prev.equals(p.gdsCd().strip())) {
                throw new IngestValidationException("같은 상품명에 다른 상품코드: " + p.gdsNm() + " → " + prev + ", " + p.gdsCd());
            }
        }

        // 2. 약관 → 상품·버전
        Map<String, ProductRow> products = new LinkedHashMap<>();
        List<VersionRow> versions = new ArrayList<>();
        for (TermsItem t : terms) {
            String name = t.gdsNm().strip();
            String code = codeByName.get(name);
            if (code == null) {
                throw new IngestValidationException("약관 상품에 대한 보험료 데이터가 없음: " + name);
            }
            // 약관코드는 URL의 p 값을 그대로 쓴다. 기존 명세의 "'P' + 상품코드 앞 6자리" 규칙은
            // 실제 데이터와 맞지 않아(4008300 → 실제 P400083, 규칙상 P400830) 검증에 쓰지 않는다.
            String termsCode = Normalizer.termsCodeFromUrl(t.stplUrlAddr());
            ProductRow existing = products.get(code);
            if (existing != null && !existing.termsCode().equals(termsCode)) {
                throw new IngestValidationException("같은 상품에 서로 다른 약관코드: " + code);
            }
            String fullName = coverageNameMap.get(code);
            products.put(code, new ProductRow(code, name, fullName, termsCode));
            versions.add(new VersionRow(code, Normalizer.parseYmd(t.aplcnBgngYmd()),
                    Normalizer.parseEndYmd(t.aplcnEndYmd()), t.stplUrlAddr().strip()));
        }
        // 오래된 버전부터 쓰면, 기존 버전의 종료일이 먼저 갱신되어 기간 겹침 제약에 걸리지 않는다
        versions.sort(Comparator.comparing(VersionRow::productCode).thenComparing(VersionRow::effectiveFrom));
        checkNoOverlap(versions);

        // 3. 보험료 → 요율
        Map<String, LocalDate> versionAtBusinessDate = new HashMap<>();
        for (String code : products.keySet()) {
            versionAtBusinessDate.put(code, versionValidAt(versions, code, businessDate));
        }
        Set<String> excluded = new TreeSet<>();
        int skippedNonMain = 0;
        Map<String, RateRow> rates = new LinkedHashMap<>();
        for (PremiumItem p : premiums) {
            String code = p.gdsCd().strip();
            if (!products.containsKey(code)) {
                excluded.add(p.gdsNm().strip() + "(" + code + ")");
                continue;
            }
            if (!MAIN_CONTRACT.equals(p.ctrtDvsnNm().strip())) {
                skippedNonMain++;
                continue;
            }
            String insureTerm = Normalizer.insureTermCode(p.insuTrm());
            RateRow row = new RateRow(code, versionAtBusinessDate.get(code),
                    Normalizer.age(p.joinAge()), Normalizer.sex(p.sex()),
                    Normalizer.payTermYears(p.pymtTrm(), insureTerm), Normalizer.isFullTermPay(p.pymtTrm()),
                    insureTerm, Normalizer.parseWon(p.prum()), Normalizer.parseUnitAmount(p.unitAmt()));
            RateRow dup = rates.putIfAbsent(row.key(), row);
            if (dup != null) {
                throw new IngestValidationException("같은 요율 키가 중복됨 (덮어쓰지 않음): " + row.key()
                        + " / " + dup.premiumWon() + "원 vs " + row.premiumWon() + "원, 계약유형 " + p.ctrtTypNm());
            }
        }

        // 4. 보장 → 문단
        Map<String, String> codeByFullName = new HashMap<>();
        coverageNameMap.forEach((code, full) -> codeByFullName.put(full.strip(), code));
        Set<String> unmapped = new TreeSet<>();
        List<CoverageLine> mappedLines = new ArrayList<>();
        for (CoverageLine l : coverageLines) {
            String code = codeByFullName.get(l.insuNm().strip());
            if (code == null || !products.containsKey(code)) {
                unmapped.add(l.insuNm().strip());
            } else {
                mappedLines.add(l);
            }
        }
        List<CoverageRow> coverages = new ArrayList<>();
        for (Paragraph para : CoverageMerger.merge(mappedLines)) {
            String code = codeByFullName.get(para.insuNm().strip());
            boolean versionExists = versions.stream().anyMatch(v ->
                    v.productCode().equals(code) && v.effectiveFrom().equals(para.effectiveFrom()));
            if (!versionExists) {
                throw new IngestValidationException("보장 적용시작일과 일치하는 약관 버전 없음: "
                        + para.insuNm() + " " + para.effectiveFrom());
            }
            coverages.add(new CoverageRow(code, para.effectiveFrom(), para.sourceLineNo(),
                    para.benefitName(), para.text(), para.note()));
        }

        return new WritePlan(new ArrayList<>(products.values()), versions, new ArrayList<>(rates.values()),
                coverages, new Report(excluded, skippedNonMain, unmapped));
    }

    private static LocalDate versionValidAt(List<VersionRow> versions, String code, LocalDate date) {
        return versions.stream()
                .filter(v -> v.productCode().equals(code))
                .filter(v -> !v.effectiveFrom().isAfter(date))
                .filter(v -> v.effectiveTo() == null || !v.effectiveTo().isBefore(date))
                .map(VersionRow::effectiveFrom)
                .findFirst()
                .orElseThrow(() -> new IngestValidationException(
                        "적재 기준일(" + date + ")에 유효한 버전이 없음: " + code));
    }

    private static void checkNoOverlap(List<VersionRow> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            VersionRow prev = sorted.get(i - 1);
            VersionRow cur = sorted.get(i);
            if (prev.productCode().equals(cur.productCode())
                    && (prev.effectiveTo() == null || !prev.effectiveTo().isBefore(cur.effectiveFrom()))) {
                throw new IngestValidationException("약관 적용기간이 겹침: " + cur.productCode()
                        + " " + prev.effectiveFrom() + "~" + prev.effectiveTo() + " / " + cur.effectiveFrom());
            }
        }
    }

    public static class IngestValidationException extends RuntimeException {
        public IngestValidationException(String message) {
            super(message);
        }
    }
}
