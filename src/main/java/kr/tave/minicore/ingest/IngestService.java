package kr.tave.minicore.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.tave.minicore.ingest.ApiModels.CoverageLine;
import kr.tave.minicore.ingest.ApiModels.Page;
import kr.tave.minicore.ingest.ApiModels.PremiumItem;
import kr.tave.minicore.ingest.ApiModels.TermsItem;
import kr.tave.minicore.ingest.IngestPlanner.WritePlan;
import kr.tave.minicore.ingest.IngestWriter.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 적재 1회 실행.
 *
 *  1) 수집: 약관 1회 → 보험료 (나이 × 성별) → 보장 (페이지 반복). 호출마다 원문을 raw_api_response에 저장
 *  2) 계획: IngestPlanner가 변환·매핑·검증 (실패 시 DB 변경 없음)
 *  3) 쓰기: IngestWriter가 한 트랜잭션으로 upsert
 *  4) 기록: ingest_log에 API별 SUCCESS/FAILED, 호출 수, 행 수, 리포트
 *
 * 서비스 요청 중에는 이 클래스를 호출하지 않는다. 서비스는 DB만 조회한다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    static final String TERMS = "TERMS";
    static final String PREMIUM = "PREMIUM";
    static final String COVERAGE = "COVERAGE";

    private final IngestProperties props;
    private final PublicApiClient client;
    private final IngestRepository repo;
    private final IngestWriter writer;
    private final ApiResponseParser parser = new ApiResponseParser(new ObjectMapper());

    public IngestService(IngestProperties props, PublicApiClient client, IngestRepository repo, IngestWriter writer) {
        this.props = props;
        this.client = client;
        this.repo = repo;
        this.writer = writer;
    }

    public WriteResult run() {
        if (props.serviceKey() == null || props.serviceKey().isBlank()) {
            throw new IllegalStateException("인증키 없음: .env의 DATA_GO_KR_SERVICE_KEY에 Decoding 키를 넣으세요");
        }
        LocalDate businessDate = repo.businessDate();
        log.info("적재 시작 (업무 기준일 {})", businessDate);

        Map<String, Long> logIds = new LinkedHashMap<>();
        Map<String, Integer> calls = new LinkedHashMap<>();
        for (String api : List.of(TERMS, PREMIUM, COVERAGE)) {
            logIds.put(api, repo.startLog(api));
            calls.put(api, 0);
        }

        try {
            List<TermsItem> terms = fetchTerms(logIds.get(TERMS), calls);
            List<Integer> emptyAges = new ArrayList<>();
            List<PremiumItem> premiums = fetchPremiums(logIds.get(PREMIUM), calls, emptyAges);
            List<CoverageLine> coverageLines = fetchCoverage(logIds.get(COVERAGE), calls);

            WritePlan plan = IngestPlanner.plan(terms, premiums, coverageLines, props.coverageNameMap(), businessDate);

            long ratesBefore = repo.count("premium_rate");
            WriteResult result = writer.write(plan);
            long ratesAfter = repo.count("premium_rate");

            String report = report(plan, emptyAges, ratesBefore, ratesAfter);
            log.info("적재 완료: {}", result);
            log.info(report);

            repo.finishLog(logIds.get(TERMS), true, calls.get(TERMS), result.products() + result.versions(), report);
            repo.finishLog(logIds.get(PREMIUM), true, calls.get(PREMIUM), result.rates(), report);
            repo.finishLog(logIds.get(COVERAGE), true, calls.get(COVERAGE), result.coverages(), report);
            return result;
        } catch (RuntimeException e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("적재 실패 (DB 데이터 변경 없음, 원문은 raw_api_response에 보관): {}", msg);
            logIds.forEach((api, id) -> repo.finishLog(id, false, calls.get(api), 0, msg));
            throw e;
        }
    }

    private List<TermsItem> fetchTerms(long logId, Map<String, Integer> calls) {
        Map<String, String> params = merge(props.terms().params());
        String body = call(TERMS, logId, props.terms().url(), params, calls);
        Page<TermsItem> page = parser.parseTerms(body);
        checkNotTruncated(TERMS, page);
        if (page.items().isEmpty()) {
            throw new IllegalStateException("약관 API 결과 0건: 파라미터(ingest.terms.params) 확인 필요");
        }
        log.info("약관 {}건", page.items().size());
        return page.items();
    }

    private List<PremiumItem> fetchPremiums(long logId, Map<String, Integer> calls, List<Integer> emptyAges) {
        IngestProperties.Premium p = props.premium();
        List<PremiumItem> all = new ArrayList<>();
        for (int age = p.ageMin(); age <= p.ageMax(); age++) {
            int rowsForAge = 0;
            for (String sex : List.of("M", "W")) {
                Map<String, String> params = merge(Map.of(
                        "GDS_NM", p.productNameQuery(),
                        "JOIN_AGE_S4", String.valueOf(age),
                        "SEX_S1", sex));
                Page<PremiumItem> page = parser.parsePremium(call(PREMIUM, logId, p.url(), params, calls));
                checkNotTruncated(PREMIUM, page);
                all.addAll(page.items());
                rowsForAge += page.items().size();
            }
            if (rowsForAge == 0) {
                emptyAges.add(age);
            }
        }
        log.info("보험료 {}행 (호출 {}회)", all.size(), calls.get(PREMIUM));
        return all;
    }

    private List<CoverageLine> fetchCoverage(long logId, Map<String, Integer> calls) {
        IngestProperties.Coverage c = props.coverage();
        List<CoverageLine> all = new ArrayList<>();
        for (int pageNo = 1; pageNo <= c.maxPages(); pageNo++) {
            Map<String, String> params = new LinkedHashMap<>(c.params());
            params.put("numOfRows", String.valueOf(c.pageSize()));
            params.put("pageNo", String.valueOf(pageNo));
            Page<CoverageLine> page = parser.parseCoverage(call(COVERAGE, logId, c.url(), merge(params), calls));
            all.addAll(page.items());
            // totalCount는 믿지 않는다(실제 응답에서 페이지 크기와 같게 옴). 덜 찬 페이지 = 마지막 페이지
            if (page.items().size() < c.pageSize()) {
                log.info("보장 {}줄 ({}페이지)", all.size(), pageNo);
                return all;
            }
        }
        throw new IllegalStateException("보장 API가 " + c.maxPages() + "페이지를 넘음: max-pages를 늘리거나 상품 필터 파라미터 확인");
    }

    private String call(String api, long logId, String url, Map<String, String> params, Map<String, Integer> calls) {
        calls.merge(api, 1, Integer::sum);
        String body;
        try {
            body = client.get(url, props.serviceKey(), params);
        } catch (RestClientResponseException e) {
            // HTTP 4xx/5xx도 본문(대개 XML 오류 메시지)을 원문으로 남긴다
            String errorBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            repo.saveRaw(logId, api, params, errorBody);
            throw new ApiResponseParser.ApiException(api + " HTTP " + e.getStatusCode().value()
                    + " (" + url + "): " + errorBody.replaceAll("\\s+", " "), e);
        }
        repo.saveRaw(logId, api, params, body);   // 파싱 전에 저장 → 파싱이 실패해도 원문이 남는다
        sleep();
        return body;
    }

    /** 페이지 처리를 하지 않는 API에서 totalCount가 받은 건수보다 크면 데이터가 잘린 것 */
    private static void checkNotTruncated(String api, Page<?> page) {
        if (page.totalCount() != null && page.totalCount() > page.items().size()) {
            throw new IllegalStateException(api + " 응답이 잘림: totalCount=" + page.totalCount()
                    + ", 받은 건수=" + page.items().size() + " → 페이지 처리 필요");
        }
    }

    private Map<String, String> merge(Map<String, String> params) {
        Map<String, String> m = new LinkedHashMap<>(props.commonParams());
        m.putAll(params);
        return m;
    }

    private void sleep() {
        try {
            Thread.sleep(props.callIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("적재 중단(interrupt)", e);
        }
    }

    private static String report(WritePlan plan, List<Integer> emptyAges, long ratesBefore, long ratesAfter) {
        IngestPlanner.Report r = plan.report();
        return String.join("\n",
                "[적재 리포트]",
                " 상품 " + plan.products().size() + " / 버전 " + plan.versions().size()
                        + " / 요율 " + plan.rates().size() + " / 보장 문단 " + plan.coverages().size(),
                " premium_rate 행 수: " + ratesBefore + " → " + ratesAfter + " (재실행이면 같아야 함)",
                " 범위 제외 상품(약관 목록에 없음): " + r.excludedPremiumProducts(),
                " 주계약 아닌 행 건너뜀: " + r.skippedNonMainContractRows(),
                " 매핑 없는 보장 상품명(coverage-name-map에 추가 필요할 수 있음): " + r.unmappedCoverageProducts(),
                " 결과 0건인 가입나이(가입 가능 나이 범위 확인용): " + compactRanges(emptyAges));
    }

    private static String compactRanges(List<Integer> ages) {
        if (ages.isEmpty()) {
            return "없음";
        }
        StringBuilder sb = new StringBuilder();
        int start = ages.get(0), prev = start;
        for (int i = 1; i <= ages.size(); i++) {
            Integer cur = i < ages.size() ? ages.get(i) : null;
            if (cur == null || cur != prev + 1) {
                sb.append(sb.isEmpty() ? "" : ", ").append(start == prev ? String.valueOf(start) : start + "~" + prev);
                if (cur != null) {
                    start = cur;
                }
            }
            if (cur != null) {
                prev = cur;
            }
        }
        return sb.toString();
    }
}
