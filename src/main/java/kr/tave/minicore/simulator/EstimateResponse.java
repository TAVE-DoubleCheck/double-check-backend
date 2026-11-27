package kr.tave.minicore.simulator;

import kr.tave.minicore.product.domain.Sex;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 예상 계산 응답 (명세 5장). 모양은 v0부터 최종 형태로 고정 —
 * v1 필드(netCost·betterIfSurrender·clauses)는 null·빈 배열로 내려주고 FE는 처음부터 이 모양으로 화면을 짠다.
 * null을 생략하지 않고 그대로 직렬화해야 하므로 @JsonInclude(NON_NULL)를 붙이지 않는다.
 */
public record EstimateResponse(
        LocalDate basisDate,
        Input input,
        int insuranceAge,
        Plans plans,
        long monthlyPremiumDiff,
        ExampleCondition exampleCondition,
        List<Point> points,
        List<String> assumptions,
        List<Clause> clauses) {

    public record Input(JoinType joinType, LocalDate birthDate, Sex sex, int payTermYears, long amount) {
    }

    public record Plans(Plan standard, Plan halfSurrender) {
    }

    public record Plan(String productCode, long monthlyPremium) {
    }

    /** 화면에 반드시 표시할 예시표 기준 조건 (명세 4장). source는 출처 쪽 + 가정 이율 */
    public record ExampleCondition(String sex, int age, long amount, int payTermYears, String source) {
    }

    public record Point(int elapsedYears, boolean paidUp, PlanPoint standard, PlanPoint halfSurrender,
                        String betterIfSurrender) {
    }

    public record PlanPoint(long paidTotal, long remainingTotal, Surrender surrender, long deathBenefit,
                            Long netCost) {
    }

    public record Surrender(ExampleBasis basis, Long amount, BigDecimal rate) {
    }

    /** v1에서 채움 (policy_clause 조회). v0는 빈 배열 */
    public record Clause(String clauseId, String article, String paragraph, String title, String plainSummary) {
    }
}
