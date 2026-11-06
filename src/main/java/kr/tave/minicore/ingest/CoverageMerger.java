package kr.tave.minicore.ingest;

import kr.tave.minicore.ingest.ApiModels.CoverageLine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 보장 API의 줄 단위 문장을 문단 단위로 합친다.
 *
 * 실제 응답 패턴 (하나로 종신, 2026-10-27 확인)
 *   1  (주계약)        | ""                             ← 섹션 머리말: 건너뜀
 *   2  ■ 사망보험금    | 보험기간 중 사망하였을 때        ← 급부 문단 시작
 *   3  ""              | ""                             ← 빈 줄 = 문단 구분
 *   4  ""              | * 보험료 납입기간 중 …          ← 급부명 없음 + '*' = 바로 위 급부의 부가조건 문단
 *   5  ""              |   여러 신체부위의 …             ← 같은 문단 이어짐
 *   6  ""              |   차회 이후의 보험료 납입을 …
 *
 * 규칙
 *   - 급부명이 있는 줄 → 새 문단 (해당 급부)
 *   - 급부명·문구 모두 빈 줄 → 문단 종료
 *   - "(…)" 형태 급부명 + 빈 문구 → 섹션 머리말, 문단 종료
 *   - 급부명 없는 줄로 문단이 시작되면 → 직전 급부 소속. '*'로 시작하면 is_note
 *   - 문단 안의 줄은 앞뒤 공백 제거 후 공백 1칸으로 연결
 */
public final class CoverageMerger {

    public record Paragraph(
            String insuNm,
            LocalDate effectiveFrom,
            int sourceLineNo,
            String benefitName,
            String text,
            boolean note
    ) {
    }

    private CoverageMerger() {
    }

    /** 여러 상품·버전이 섞인 줄 목록을 (상품명, 적용시작일)별로 묶어 문단으로 변환 */
    public static List<Paragraph> merge(List<CoverageLine> lines) {
        Map<String, List<CoverageLine>> groups = new LinkedHashMap<>();
        for (CoverageLine l : lines) {
            groups.computeIfAbsent(l.insuNm() + "|" + l.aplcnBgngYmd(), k -> new ArrayList<>()).add(l);
        }
        List<Paragraph> result = new ArrayList<>();
        for (List<CoverageLine> group : groups.values()) {
            result.addAll(mergeOneDocument(group));
        }
        return result;
    }

    static List<Paragraph> mergeOneDocument(List<CoverageLine> group) {
        List<CoverageLine> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingInt(CoverageLine::lineNo));
        checkNoDuplicateLineNo(sorted);

        String insuNm = sorted.get(0).insuNm();
        LocalDate from = Normalizer.parseYmd(sorted.get(0).aplcnBgngYmd());

        List<Paragraph> out = new ArrayList<>();
        String currentBenefit = null;
        Builder open = null;

        for (CoverageLine line : sorted) {
            String name = Normalizer.benefitName(line.bnfiNm());
            String text = line.sntcCn() == null ? "" : line.sntcCn().strip();

            if (name.isEmpty() && text.isEmpty()) {                       // 빈 줄
                open = flush(open, out);
                continue;
            }
            if (!name.isEmpty() && text.isEmpty() && name.startsWith("(") && name.endsWith(")")) { // 섹션 머리말
                open = flush(open, out);
                currentBenefit = null;
                continue;
            }
            if (!name.isEmpty()) {                                        // 새 급부
                open = flush(open, out);
                currentBenefit = name;
                open = new Builder(insuNm, from, line.lineNo(), name, false);
                open.append(text);
                continue;
            }
            // 급부명 없는 문구 줄
            if (open == null) {
                if (currentBenefit == null) {
                    throw new IllegalStateException("소속 급부를 알 수 없는 문구: " + insuNm + " line " + line.lineNo());
                }
                boolean note = text.startsWith("*");
                open = new Builder(insuNm, from, line.lineNo(), currentBenefit, note);
                open.append(note ? text.substring(1).strip() : text);
            } else {
                open.append(text);
            }
        }
        flush(open, out);
        return out;
    }

    private static void checkNoDuplicateLineNo(List<CoverageLine> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).lineNo() == sorted.get(i - 1).lineNo()) {
                throw new IllegalStateException("같은 SNTC_LINE_NO가 중복됨: "
                        + sorted.get(i).insuNm() + " line " + sorted.get(i).lineNo());
            }
        }
    }

    private static Builder flush(Builder b, List<Paragraph> out) {
        if (b != null && !b.text.isEmpty()) {
            out.add(new Paragraph(b.insuNm, b.from, b.startLine, b.benefit, b.text.toString(), b.note));
        }
        return null;
    }

    private static final class Builder {
        final String insuNm;
        final LocalDate from;
        final int startLine;
        final String benefit;
        final boolean note;
        final StringBuilder text = new StringBuilder();

        Builder(String insuNm, LocalDate from, int startLine, String benefit, boolean note) {
            this.insuNm = insuNm;
            this.from = from;
            this.startLine = startLine;
            this.benefit = benefit;
            this.note = note;
        }

        void append(String s) {
            if (s.isEmpty()) {
                return;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(s);
        }
    }
}
