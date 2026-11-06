package kr.tave.minicore.product.quote;

import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;

import java.time.LocalDate;
import java.time.Period;

/**
 * 보험나이 (약관 제23조②).
 * 계약일 기준 만 나이를 계산한 뒤, 남은 끝수가 6개월 이상이면 1세 올리고 6개월 미만이면 버린다.
 * 예) 1988-10-02생, 계약일 2016-04-13 → 27년 6월 11일 → 28세.
 */
public final class InsuranceAge {

    private InsuranceAge() {
    }

    public static int of(LocalDate birthDate, LocalDate contractDate) {
        if (birthDate.isAfter(contractDate)) {
            throw new ApiException(ErrorCode.BIRTH_DATE_IN_FUTURE,
                    "생년월일(" + birthDate + ")이 계약일(" + contractDate + ") 이후입니다");
        }
        Period p = Period.between(birthDate, contractDate);
        return p.getYears() + (p.getMonths() >= 6 ? 1 : 0);
    }
}
