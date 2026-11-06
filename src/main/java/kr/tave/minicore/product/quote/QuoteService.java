package kr.tave.minicore.product.quote;

import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.product.ProductProperties;
import kr.tave.minicore.product.domain.PremiumRate;
import kr.tave.minicore.product.domain.PremiumRateId;
import kr.tave.minicore.product.domain.ProductVersion;
import kr.tave.minicore.product.domain.Sex;
import kr.tave.minicore.product.repository.AgeRangeView;
import kr.tave.minicore.product.repository.PremiumRateRepository;
import kr.tave.minicore.product.repository.ProductRepository;
import kr.tave.minicore.product.repository.ProductVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 보험료 계산 (FE 미리보기 + BE B 가입 처리 공용).
 * 가입 처리는 이 결과의 versionId·monthlyPremium·insuranceAge를 그대로 계약에 고정한다.
 */
@Service
@Transactional(readOnly = true)
public class QuoteService {

    /** FE가 화면에 표시하는 단순화·가정. 해소되면 여기서 지운다. */
    static final List<String> ASSUMPTIONS = List.of(
            "월납 기준 (확인 필요)",
            "고액계약 할인 미반영 (단순화)");

    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final PremiumRateRepository rates;
    private final ProductProperties productProps;
    private final QuoteProperties quoteProps;
    private final ClockService clock;

    public QuoteService(ProductRepository products, ProductVersionRepository versions,
                        PremiumRateRepository rates, ProductProperties productProps,
                        QuoteProperties quoteProps, ClockService clock) {
        this.products = products;
        this.versions = versions;
        this.rates = rates;
        this.productProps = productProps;
        this.quoteProps = quoteProps;
        this.clock = clock;
    }

    public QuoteResult quote(String productCode, LocalDate birthDate, Sex sex,
                             int payTermYears, String insureTermCode, long amount) {
        // 1. 상품
        products.findById(productCode)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품코드 없음: " + productCode));

        // 2. 1단계 가입 허용 여부 (설계 리뷰 안건 2)
        if (!productProps.isSubscribable(productCode)) {
            throw new ApiException(ErrorCode.PRODUCT_NOT_SUBSCRIBABLE, "1단계에서는 가입할 수 없는 상품입니다");
        }

        // 3. 업무 날짜 기준 유효 버전 (계약은 이 버전에 고정된다)
        LocalDate today = clock.today();
        ProductVersion version = versions.findEffective(productCode, today)
                .orElseThrow(() -> new ApiException(ErrorCode.NO_EFFECTIVE_VERSION,
                        "오늘(" + today + ") 유효한 상품 버전이 없습니다: " + productCode));

        // 4. 가입금액 검증 (단위 배수 → 범위)
        long unitAmountWon = unitAmountOf(version.getId());
        if (amount % unitAmountWon != 0) {
            throw new ApiException(ErrorCode.AMOUNT_NOT_UNIT_MULTIPLE,
                    "가입금액은 기준단위금액(" + unitAmountWon + "원)의 배수여야 합니다: " + amount);
        }
        long min = unitAmountWon * quoteProps.amountMinUnits();
        long max = unitAmountWon * quoteProps.amountMaxUnits();
        if (amount < min || amount > max) {
            throw new ApiException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "가입금액 범위(" + min + "~" + max + "원) 밖: " + amount);
        }

        // 5. 보험나이 (계약일 = 업무 날짜)
        int insuranceAge = InsuranceAge.of(birthDate, today);

        // 6. 요율 1행
        PremiumRate rate = rates.findById(new PremiumRateId(
                        version.getId(), (short) insuranceAge, sex.code(), (short) payTermYears, insureTermCode))
                .orElseThrow(() -> rateNotFound(version.getId(), sex, insuranceAge, payTermYears, insureTermCode));

        // 7. 월 보험료
        long monthlyPremium = PremiumCalculator.monthlyPremium(rate.getPremiumWon(), rate.getUnitAmountWon(), amount);

        return new QuoteResult(productCode, version.getId(), version.getEffectiveFrom(),
                insuranceAge, sex, payTermYears, rate.isFullTermPay(), insureTermCode,
                amount, rate.getUnitAmountWon(), rate.getPremiumWon(), monthlyPremium,
                today, ASSUMPTIONS);
    }

    /** 요율이 없는 이유를 메시지에 담는다: 나이 범위 밖인지, 조합이 없는 건지 */
    private ApiException rateNotFound(long versionId, Sex sex, int insuranceAge,
                                      int payTermYears, String insureTermCode) {
        AgeRangeView range = rates.findJoinAgeRange(versionId, sex.code());
        String reason;
        if (range.getMin() == null) {
            reason = "해당 성별의 요율이 없습니다";
        } else if (insuranceAge < range.getMin() || insuranceAge > range.getMax()) {
            reason = "보험나이 " + insuranceAge + "세는 가입 범위(" + range.getMin() + "~" + range.getMax() + "세) 밖";
        } else {
            reason = "납입기간·보험기간 조합이 없음 (납입 " + payTermYears + "년, 보험기간 코드 " + insureTermCode + ")";
        }
        return new ApiException(ErrorCode.RATE_NOT_FOUND, "해당 조건의 요율이 없습니다 (" + reason + ")");
    }

    /** 버전의 기준단위금액. 현재 데이터는 1,000만 원 1종류다 — 여러 개면 데이터 이상이므로 중단. */
    private long unitAmountOf(long versionId) {
        List<Long> units = rates.findDistinctUnitAmounts(versionId);
        if (units.size() != 1) {
            throw new IllegalStateException("기준단위금액이 1종류가 아님 (version " + versionId + "): " + units);
        }
        return units.get(0);
    }
}
