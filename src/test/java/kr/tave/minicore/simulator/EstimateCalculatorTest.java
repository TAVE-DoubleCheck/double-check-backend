package kr.tave.minicore.simulator;

import kr.tave.minicore.simulator.EstimateCalculator.Condition;
import kr.tave.minicore.simulator.EstimateCalculator.ExampleRow;
import kr.tave.minicore.simulator.EstimateCalculator.ExampleSet;
import kr.tave.minicore.simulator.EstimateCalculator.PlanInput;
import kr.tave.minicore.simulator.EstimateCalculator.PlanPoint;
import kr.tave.minicore.simulator.EstimateCalculator.Point;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 명세 6장 기대값 표 검증 (1종 · 남자 40세 · 1,000만 원 · 30년납, 상품요약서 p.63).
 * 예시표 행은 seed/reference_seed.sql(V7 적재분)과 같은 값이다.
 */
class EstimateCalculatorTest {

    // 월 보험료는 QuoteService가 계산한 값 (DB 요율 18,190 / 16,580 — 예시표 역산값과 일치 확인됨)
    private static final long STANDARD_PREMIUM = 18_190;
    private static final long HALF_PREMIUM = 16_580;
    private static final long AMOUNT = 10_000_000;

    private static final ExampleSet STANDARD_EXAMPLE = new ExampleSet('M', 40, AMOUNT, 30, List.of(
            new ExampleRow(1, 218_280, 0),
            new ExampleRow(3, 654_840, 372_710),
            new ExampleRow(5, 1_091_400, 848_610),
            new ExampleRow(7, 1_527_960, 1_214_430),
            new ExampleRow(10, 2_182_800, 1_792_580),
            new ExampleRow(20, 4_365_600, 3_995_520),
            new ExampleRow(30, 6_548_400, 6_680_600),
            new ExampleRow(50, 6_548_400, 8_881_090),
            new ExampleRow(70, 6_548_400, 9_882_300)));

    private static final ExampleSet HALF_EXAMPLE = new ExampleSet('M', 40, AMOUNT, 30, List.of(
            new ExampleRow(1, 198_960, 0),
            new ExampleRow(3, 596_880, 186_350),
            new ExampleRow(5, 994_800, 424_300),
            new ExampleRow(7, 1_392_720, 607_210),
            new ExampleRow(10, 1_989_600, 896_290),
            new ExampleRow(20, 3_979_200, 1_997_760),
            new ExampleRow(30, 5_968_800, 6_680_600),
            new ExampleRow(50, 5_968_800, 8_881_090),
            new ExampleRow(70, 5_968_800, 9_882_300)));

    private static List<Point> examplePoints() {
        return EstimateCalculator.points(new Condition('M', 40, AMOUNT, 30),
                new PlanInput(STANDARD_PREMIUM, STANDARD_EXAMPLE),
                new PlanInput(HALF_PREMIUM, HALF_EXAMPLE));
    }

    @Test
    void 명세_6장_표준형_7개_시점() {
        List<Point> points = examplePoints();
        assertThat(points).hasSize(7);
        // 경과, 낸 돈, 남은 돈, 환급금, 환급률 (명세 6장 표)
        assertStandard(points.get(0), 1, 218_280, 6_330_120, 0, "0.0");
        assertStandard(points.get(1), 3, 654_840, 5_893_560, 372_710, "56.9");
        assertStandard(points.get(2), 5, 1_091_400, 5_457_000, 848_610, "77.8");
        assertStandard(points.get(3), 7, 1_527_960, 5_020_440, 1_214_430, "79.5");
        assertStandard(points.get(4), 10, 2_182_800, 4_365_600, 1_792_580, "82.1");
        assertStandard(points.get(5), 20, 4_365_600, 2_182_800, 3_995_520, "91.5");
        assertStandard(points.get(6), 30, 6_548_400, 0, 6_680_600, "102.0");
    }

    @Test
    void 명세_6장_50퍼센트형_7개_시점() {
        List<Point> points = examplePoints();
        assertHalf(points.get(0), 1, 198_960, 5_769_840, 0, "0.0");
        assertHalf(points.get(1), 3, 596_880, 5_371_920, 186_350, "31.2");
        assertHalf(points.get(2), 5, 994_800, 4_974_000, 424_300, "42.7");
        assertHalf(points.get(3), 7, 1_392_720, 4_576_080, 607_210, "43.6");
        assertHalf(points.get(4), 10, 1_989_600, 3_979_200, 896_290, "45.0");
        assertHalf(points.get(5), 20, 3_979_200, 1_989_600, 1_997_760, "50.2");
        assertHalf(points.get(6), 30, 5_968_800, 0, 6_680_600, "111.9");
    }

    @Test
    void 사망보험금은_모든_시점에서_가입금액() {
        for (Point p : examplePoints()) {
            assertThat(p.standard().deathBenefit()).isEqualTo(AMOUNT);
            assertThat(p.halfSurrender().deathBenefit()).isEqualTo(AMOUNT);
        }
    }

    @Test
    void 시점_목록_30년납은_7개_마지막만_paidUp() {
        List<Point> points = examplePoints();
        assertThat(points).extracting(Point::elapsedYears).containsExactly(1, 3, 5, 7, 10, 20, 30);
        assertThat(points).extracting(Point::paidUp)
                .containsExactly(false, false, false, false, false, false, true);
    }

    @Test
    void 시점_목록_10년납은_1_3_5_7_10이고_10이_paidUp() {
        List<Point> points = EstimateCalculator.points(new Condition('M', 40, AMOUNT, 10),
                new PlanInput(STANDARD_PREMIUM, STANDARD_EXAMPLE),
                new PlanInput(HALF_PREMIUM, HALF_EXAMPLE));
        assertThat(points).extracting(Point::elapsedYears).containsExactly(1, 3, 5, 7, 10);
        assertThat(points).extracting(Point::paidUp).containsExactly(false, false, false, false, true);
    }

    @Test
    void basis_예시_조건과_완전히_같으면_EXAMPLE_EXACT_금액과_환급률() {
        Point p = examplePoints().get(4); // 10년
        assertThat(p.standard().surrender().basis()).isEqualTo(ExampleBasis.EXAMPLE_EXACT);
        assertThat(p.standard().surrender().amount()).isEqualTo(1_792_580);
        assertThat(p.standard().surrender().rate()).isEqualByComparingTo("82.1");
    }

    @Test
    void basis_30년납이지만_나이가_다르면_REFERENCE_RATE_금액은_null_환급률만() {
        List<Point> points = EstimateCalculator.points(new Condition('M', 45, AMOUNT, 30),
                new PlanInput(STANDARD_PREMIUM, STANDARD_EXAMPLE),
                new PlanInput(HALF_PREMIUM, HALF_EXAMPLE));
        EstimateCalculator.Surrender s = points.get(4).standard().surrender();
        assertThat(s.basis()).isEqualTo(ExampleBasis.REFERENCE_RATE);
        assertThat(s.amount()).isNull();
        assertThat(s.rate()).isEqualByComparingTo("82.1");
    }

    @Test
    void basis_30년납이_아니면_NOT_AVAILABLE_금액도_환급률도_null() {
        List<Point> points = EstimateCalculator.points(new Condition('M', 40, AMOUNT, 10),
                new PlanInput(STANDARD_PREMIUM, STANDARD_EXAMPLE),
                new PlanInput(HALF_PREMIUM, HALF_EXAMPLE));
        EstimateCalculator.Surrender s = points.get(4).standard().surrender();
        assertThat(s.basis()).isEqualTo(ExampleBasis.NOT_AVAILABLE);
        assertThat(s.amount()).isNull();
        assertThat(s.rate()).isNull();
    }

    @Test
    void 환급률은_우리가_계산한_낸_돈이_아니라_예시표_납입액이_분모다() {
        // 성별이 달라 REFERENCE_RATE인 경우: 낸 돈은 여성 보험료 기준이지만 환급률은 예시표(남 40세) 값 그대로
        long womanPremium = 16_100;
        List<Point> points = EstimateCalculator.points(new Condition('W', 40, AMOUNT, 30),
                new PlanInput(womanPremium, STANDARD_EXAMPLE),
                new PlanInput(14_640, HALF_EXAMPLE));
        PlanPoint p = points.get(4).standard();
        assertThat(p.paidTotal()).isEqualTo(womanPremium * 120);
        assertThat(p.surrender().rate()).isEqualByComparingTo("82.1");
    }

    private static void assertStandard(Point p, int years, long paid, long remaining, long surrender, String rate) {
        assertPlan(p, p.standard(), years, paid, remaining, surrender, rate);
    }

    private static void assertHalf(Point p, int years, long paid, long remaining, long surrender, String rate) {
        assertPlan(p, p.halfSurrender(), years, paid, remaining, surrender, rate);
    }

    private static void assertPlan(Point point, PlanPoint plan, int years,
                                   long paid, long remaining, long surrender, String rate) {
        assertThat(point.elapsedYears()).isEqualTo(years);
        assertThat(plan.paidTotal()).as("%d년 낸 돈", years).isEqualTo(paid);
        assertThat(plan.remainingTotal()).as("%d년 남은 돈", years).isEqualTo(remaining);
        assertThat(plan.surrender().basis()).isEqualTo(ExampleBasis.EXAMPLE_EXACT);
        assertThat(plan.surrender().amount()).as("%d년 환급금", years).isEqualTo(surrender);
        assertThat(plan.surrender().rate()).as("%d년 환급률", years).isEqualByComparingTo(new BigDecimal(rate));
    }
}
