package kr.tave.minicore.simulator;

import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.product.domain.Sex;
import kr.tave.minicore.product.quote.InsuranceAge;
import kr.tave.minicore.product.quote.QuoteResult;
import kr.tave.minicore.product.quote.QuoteService;
import kr.tave.minicore.product.repository.AgeRangeView;
import kr.tave.minicore.product.repository.PremiumRateRepository;
import kr.tave.minicore.product.repository.ProductVersionRepository;
import kr.tave.minicore.simulator.EstimateCalculator.Condition;
import kr.tave.minicore.simulator.EstimateCalculator.ExampleSet;
import kr.tave.minicore.simulator.EstimateCalculator.PlanInput;
import kr.tave.minicore.simulator.SurrenderExampleRepository.ExampleSetRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 예상 계산 v0 (명세 4장): 시스템 날짜 읽기 → 입력 검증 → quote로 두 유형 월 보험료 → 예시표 조회 → 계산기.
 * DB에 쓰지 않고 시스템 날짜도 바꾸지 않는다. 같은 입력 + 같은 시스템 날짜 → 항상 같은 결과.
 *
 * 월 보험료는 QuoteService 결과를 그대로 쓴다 — quote 규칙(①)이 바뀌면 이 API 결과도 같이 바뀐다.
 */
@Service
@Transactional(readOnly = true)
public class EstimateService {

    /** v0 지원 납입기간. 80세납은 완납 시점 계산 규칙 확인 전이라 제외 (명세 3장) */
    private static final Set<Integer> SUPPORTED_PAY_TERMS = Set.of(10, 15, 20, 30);

    /** 사업방법서 3.가: 가입금액 1,000만~4,000만 원, 500만 원 단위. 2종 66세 이상은 2,000만 원까지 */
    private static final long AMOUNT_MIN = 10_000_000L;
    private static final long AMOUNT_MAX = 40_000_000L;
    private static final long AMOUNT_UNIT = 5_000_000L;
    private static final int SIMPLIFIED_REDUCED_AGE = 66;
    private static final long SIMPLIFIED_REDUCED_MAX = 20_000_000L;

    /** 보험기간은 종신 고정 (premium_rate.insure_term_code '99') */
    private static final String WHOLE_LIFE = "99";

    private final QuoteService quote;
    private final SurrenderExampleRepository surrenderExamples;
    private final ProductVersionRepository versions;
    private final PremiumRateRepository rates;
    private final ClockService clock;

    public EstimateService(QuoteService quote, SurrenderExampleRepository surrenderExamples,
                           ProductVersionRepository versions, PremiumRateRepository rates, ClockService clock) {
        this.quote = quote;
        this.surrenderExamples = surrenderExamples;
        this.versions = versions;
        this.rates = rates;
        this.clock = clock;
    }

    public EstimateResponse estimate(JoinType joinType, LocalDate birthDate, Sex sex,
                                     int payTermYears, long amount) {
        LocalDate basisDate = clock.today();
        int insuranceAge = InsuranceAge.of(birthDate, basisDate);
        validate(joinType, sex, insuranceAge, payTermYears, amount, basisDate);

        QuoteResult standard = quoteOrNotFound(joinType.standardCode(), birthDate, sex, payTermYears, amount);
        QuoteResult half = quoteOrNotFound(joinType.halfSurrenderCode(), birthDate, sex, payTermYears, amount);

        ExampleSetRow standardExample = exampleOf(joinType.standardCode());
        ExampleSetRow halfExample = exampleOf(joinType.halfSurrenderCode());

        Condition condition = new Condition(sex.code(), insuranceAge, amount, payTermYears);
        List<EstimateCalculator.Point> points = EstimateCalculator.points(condition,
                new PlanInput(standard.monthlyPremium(), toCalculatorSet(standardExample)),
                new PlanInput(half.monthlyPremium(), toCalculatorSet(halfExample)));

        String assumedRatePercent = percentOf(standardExample.assumedDisclosedRate());
        return new EstimateResponse(
                basisDate,
                new EstimateResponse.Input(joinType, birthDate, sex, payTermYears, amount),
                insuranceAge,
                new EstimateResponse.Plans(
                        new EstimateResponse.Plan(standard.productCode(), standard.monthlyPremium()),
                        new EstimateResponse.Plan(half.productCode(), half.monthlyPremium())),
                standard.monthlyPremium() - half.monthlyPremium(),
                new EstimateResponse.ExampleCondition(
                        String.valueOf(standardExample.sex()), standardExample.joinAge(),
                        standardExample.sumInsuredWon(), standardExample.payTermYears(),
                        "상품요약서 p." + standardExample.sourcePage()
                                + " (신공시이율Ⅳ " + assumedRatePercent + "% 유지 가정)"),
                points.stream().map(EstimateService::toResponsePoint).toList(),
                List.of("매달 정상 납부 가정",
                        "사망보험금 = 가입금액 (플러스적립금 제외, 단순화)",
                        "해약환급금은 상품요약서 예시표 기준 (신공시이율Ⅳ " + assumedRatePercent + "% 유지 가정)"),
                List.of());
    }

    private void validate(JoinType joinType, Sex sex, int insuranceAge, int payTermYears,
                          long amount, LocalDate basisDate) {
        if (!SUPPORTED_PAY_TERMS.contains(payTermYears)) {
            throw new ApiException(ErrorCode.PAY_TERM_NOT_SUPPORTED,
                    "v0는 10·15·20·30년납만 지원합니다: " + payTermYears + "년납");
        }
        if (amount < AMOUNT_MIN || amount > AMOUNT_MAX || amount % AMOUNT_UNIT != 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    "가입금액은 1,000만~4,000만 원, 500만 원 단위여야 합니다: " + amount);
        }
        if (joinType == JoinType.SIMPLIFIED && insuranceAge >= SIMPLIFIED_REDUCED_AGE
                && amount > SIMPLIFIED_REDUCED_MAX) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    "2종(간편가입) " + SIMPLIFIED_REDUCED_AGE + "세 이상은 2,000만 원까지 가입할 수 있습니다: " + amount);
        }
        // 납입기간별 가입나이 (사업방법서 2.가·나). 약관에 명시가 없어 요율표 존재 범위로 판단한다 (quote와 같은 기준)
        versions.findEffective(joinType.standardCode(), basisDate).ifPresent(version -> {
            AgeRangeView range = rates.findJoinAgeRange(version.getId(), sex.code(),
                    (short) payTermYears, WHOLE_LIFE);
            if (range.getMin() != null && (insuranceAge < range.getMin() || insuranceAge > range.getMax())) {
                throw new ApiException(ErrorCode.AGE_NOT_ALLOWED,
                        "보험나이 " + insuranceAge + "세는 " + payTermYears + "년납 가입 범위("
                                + range.getMin() + "~" + range.getMax() + "세) 밖입니다");
            }
        });
    }

    /** 사전 검증을 지나고도 요율 행이 없으면 404 (명세 3장: PREMIUM_RATE_NOT_FOUND) */
    private QuoteResult quoteOrNotFound(String productCode, LocalDate birthDate, Sex sex,
                                        int payTermYears, long amount) {
        try {
            return quote.quote(productCode, birthDate, sex, payTermYears, WHOLE_LIFE, amount);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.RATE_NOT_FOUND) {
                throw new ApiException(ErrorCode.PREMIUM_RATE_NOT_FOUND,
                        "해당 조건의 보험료표 행이 없습니다 (상품 " + productCode + "): " + e.getMessage());
            }
            throw e;
        }
    }

    private ExampleSetRow exampleOf(String productCode) {
        // 공시값 시드(reference_seed)가 빠진 환경은 데이터 적재 문제라 서버 오류로 드러낸다
        return surrenderExamples.findByProductCode(productCode)
                .orElseThrow(() -> new IllegalStateException("해약환급금 예시표 없음: " + productCode));
    }

    private static ExampleSet toCalculatorSet(ExampleSetRow row) {
        return new ExampleSet(row.sex(), row.joinAge(), row.sumInsuredWon(), row.payTermYears(), row.rows());
    }

    private static EstimateResponse.Point toResponsePoint(EstimateCalculator.Point p) {
        return new EstimateResponse.Point(p.elapsedYears(), p.paidUp(),
                toResponsePlanPoint(p.standard()), toResponsePlanPoint(p.halfSurrender()),
                null); // betterIfSurrender는 v1 (netCost가 생겨야 비교 가능)
    }

    private static EstimateResponse.PlanPoint toResponsePlanPoint(EstimateCalculator.PlanPoint p) {
        return new EstimateResponse.PlanPoint(p.paidTotal(), p.remainingTotal(),
                new EstimateResponse.Surrender(p.surrender().basis(), p.surrender().amount(), p.surrender().rate()),
                p.deathBenefit(),
                null); // netCost는 v1
    }

    /** 0.0205 → "2.05" (화면 표기용) */
    private static String percentOf(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString();
    }
}
