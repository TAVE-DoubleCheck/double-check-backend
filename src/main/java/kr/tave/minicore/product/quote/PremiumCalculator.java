package kr.tave.minicore.product.quote;

import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;

/**
 * 월 보험료 계산. 금액은 전부 원 단위 정수, 반올림 없음.
 *
 * 단순화: 약관 요약서에 "고액계약 할인(주계약)"이 있으나 기준·비율이 약관에 없다(사업방법서 영역).
 * 1단계는 할인 없이 기준단위금액에 정비례한다. 할인이 확인되면 이 메서드만 바꾼다.
 */
public final class PremiumCalculator {

    private PremiumCalculator() {
    }

    /**
     * @param premiumWon    기준단위금액당 월 보험료 (요율표 premium_won)
     * @param unitAmountWon 기준단위금액 (요율표 unit_amount_won, 1,000만 원)
     * @param amount        가입금액 (원). 기준단위금액의 배수만 허용
     */
    public static long monthlyPremium(long premiumWon, long unitAmountWon, long amount) {
        if (amount % unitAmountWon != 0) {
            throw new ApiException(ErrorCode.AMOUNT_NOT_UNIT_MULTIPLE,
                    "가입금액은 기준단위금액(" + unitAmountWon + "원)의 배수여야 합니다: " + amount);
        }
        return premiumWon * (amount / unitAmountWon);
    }
}
