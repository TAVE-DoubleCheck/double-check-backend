package kr.tave.minicore.product.quote;

import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 월 보험료 = premium_won × (가입금액 ÷ 기준단위금액). 정수 연산만, 할인 미반영(단순화). */
class PremiumCalculatorTest {

    private static final long UNIT = 10_000_000L;   // 기준단위금액 1,000만 원

    @Test
    void 기준단위_5배_가입금액_5천만원() {
        assertThat(PremiumCalculator.monthlyPremium(33_070L, UNIT, 50_000_000L)).isEqualTo(165_350L);
    }

    @Test
    void 기준단위_1배_가입금액_1천만원() {
        assertThat(PremiumCalculator.monthlyPremium(33_070L, UNIT, 10_000_000L)).isEqualTo(33_070L);
    }

    @Test
    void 기준단위_배수가_아니면_예외_1500만원() {
        assertThatThrownBy(() -> PremiumCalculator.monthlyPremium(33_070L, UNIT, 15_000_000L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.AMOUNT_NOT_UNIT_MULTIPLE));
    }
}
