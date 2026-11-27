package kr.tave.minicore.simulator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 시점별 예상값 계산 (명세 4장). DB·Spring에 의존하지 않는 순수 클래스 —
 * 6주차 대조 테스트에서 실제 엔진(청구서 발행·납부) 결과와 비교할 대상이다.
 *
 * 입력 = 입력 조건 + 유형별 (월 보험료, 예시표) 2개 → 출력 = 시점별 points.
 * 월 보험료는 QuoteService가 계산한 값을 받기만 하고, 여기서 다시 계산하지 않는다.
 * 해약환급금은 예시표 행을 그대로 읽는다 (시점 사이 보간 안 함, 가입금액 비례 환산 안 함).
 */
public final class EstimateCalculator {

    /** 기준 시점 (명세 4장). 납입기간보다 작은 값만 쓰고 마지막에 납입 완료 시점(= 납입기간)을 더한다. */
    static final int[] CHECK_YEARS = {1, 3, 5, 7, 10, 20};

    /** 입력 조건 중 예시표 조건(sex·join_age·sum_insured·pay_term)과 비교할 부분 */
    public record Condition(char sex, int insuranceAge, long amount, int payTermYears) {
    }

    /** 예시표 한 행 (surrender_example). rate 분모는 예시표 납입액이지 우리가 계산한 낸 돈이 아니다 */
    public record ExampleRow(int elapsedYears, long paidPremiumWon, long surrenderWon) {
    }

    /** 유형 하나의 예시표 (surrender_example_set + 행들) */
    public record ExampleSet(char sex, int joinAge, long sumInsuredWon, int payTermYears, List<ExampleRow> rows) {
    }

    /** 유형 하나의 계산 입력 */
    public record PlanInput(long monthlyPremium, ExampleSet example) {
    }

    public record Surrender(ExampleBasis basis, Long amount, BigDecimal rate) {
    }

    public record PlanPoint(long paidTotal, long remainingTotal, Surrender surrender, long deathBenefit) {
    }

    public record Point(int elapsedYears, boolean paidUp, PlanPoint standard, PlanPoint halfSurrender) {
    }

    private EstimateCalculator() {
    }

    public static List<Point> points(Condition condition, PlanInput standard, PlanInput halfSurrender) {
        ExampleBasis standardBasis = basisOf(condition, standard.example());
        ExampleBasis halfBasis = basisOf(condition, halfSurrender.example());

        List<Point> points = new ArrayList<>();
        for (int years : elapsedYearsOf(condition.payTermYears())) {
            points.add(new Point(years, years >= condition.payTermYears(),
                    planPoint(condition, standard, standardBasis, years),
                    planPoint(condition, halfSurrender, halfBasis, years)));
        }
        return points;
    }

    /** 예시표 적용 기준 (명세 4장 표): 납입기간 다름 → 없음 / 같고 나머지 조건도 같음 → 정확 / 같지만 조건 다름 → 참고 */
    static ExampleBasis basisOf(Condition c, ExampleSet example) {
        if (c.payTermYears() != example.payTermYears()) {
            return ExampleBasis.NOT_AVAILABLE;
        }
        boolean exact = c.sex() == example.sex()
                && c.insuranceAge() == example.joinAge()
                && c.amount() == example.sumInsuredWon();
        return exact ? ExampleBasis.EXAMPLE_EXACT : ExampleBasis.REFERENCE_RATE;
    }

    static List<Integer> elapsedYearsOf(int payTermYears) {
        List<Integer> years = new ArrayList<>();
        for (int y : CHECK_YEARS) {
            if (y < payTermYears) {
                years.add(y);
            }
        }
        years.add(payTermYears);
        return years;
    }

    private static PlanPoint planPoint(Condition c, PlanInput plan, ExampleBasis basis, int elapsedYears) {
        long payTermMonths = c.payTermYears() * 12L;
        long paidTotal = plan.monthlyPremium() * Math.min(elapsedYears * 12L, payTermMonths);
        long remainingTotal = plan.monthlyPremium() * payTermMonths - paidTotal;
        return new PlanPoint(paidTotal, remainingTotal, surrenderAt(plan.example(), basis, elapsedYears), c.amount());
    }

    private static Surrender surrenderAt(ExampleSet example, ExampleBasis basis, int elapsedYears) {
        if (basis == ExampleBasis.NOT_AVAILABLE) {
            return new Surrender(basis, null, null);
        }
        Map<Integer, ExampleRow> byYear = example.rows().stream()
                .collect(Collectors.toMap(ExampleRow::elapsedYears, Function.identity()));
        ExampleRow row = byYear.get(elapsedYears);
        if (row == null) { // 예시표에 없는 시점은 보간하지 않는다
            return new Surrender(basis, null, null);
        }
        BigDecimal rate = BigDecimal.valueOf(row.surrenderWon() * 100L)
                .divide(BigDecimal.valueOf(row.paidPremiumWon()), 1, RoundingMode.HALF_UP);
        Long amount = basis == ExampleBasis.EXAMPLE_EXACT ? row.surrenderWon() : null;
        return new Surrender(basis, amount, rate);
    }
}
