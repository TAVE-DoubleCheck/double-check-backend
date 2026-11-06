package kr.tave.minicore.product.quote;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 납입 플랜. "80세납"은 원본 표기가 아니라 "납입년수 = 80 − 가입나이" 관계로 유도한 플랜이라
 * TO_AGE(만기 나이)로 중립적으로 표현한다. 화면 문구는 FE가 정한다.
 *
 * @param years FIXED_YEARS일 때 납입년수
 * @param toAge TO_AGE일 때 납입 종료 나이 (실제 납입년수 = toAge − 보험나이, QuoteResult.payTermYears)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayTermPlan(Type type, Integer years, Integer toAge) {

    public enum Type { FIXED_YEARS, TO_AGE }

    private static final Pattern PARAM = Pattern.compile("^(FIXED_YEARS|TO_AGE)_(\\d{1,3})$");

    public static PayTermPlan fixedYears(int years) {
        return new PayTermPlan(Type.FIXED_YEARS, years, null);
    }

    public static PayTermPlan toAge(int toAge) {
        return new PayTermPlan(Type.TO_AGE, null, toAge);
    }

    /** 쿼리 파라미터 형태: "FIXED_YEARS_10" / "TO_AGE_80" */
    public static PayTermPlan parse(String param) {
        Matcher m = PARAM.matcher(param);
        if (!m.matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "payTermPlan 형식 오류 (FIXED_YEARS_<년수> 또는 TO_AGE_<나이>): " + param);
        }
        int value = Integer.parseInt(m.group(2));
        return "FIXED_YEARS".equals(m.group(1)) ? fixedYears(value) : toAge(value);
    }
}
