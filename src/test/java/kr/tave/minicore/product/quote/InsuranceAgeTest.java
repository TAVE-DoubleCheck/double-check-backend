package kr.tave.minicore.product.quote;

import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 보험나이 규칙: 만 나이 + 끝수 6개월 이상 올림 (약관 제23조②) */
class InsuranceAgeTest {

    @Test
    void insuranceAge_약관제23조_예시_1988_10_02_to_2016_04_13_is_28() {
        // 만 27년 6월 11일 → 끝수 6개월 이상 → 28세
        assertThat(InsuranceAge.of(LocalDate.of(1988, 10, 2), LocalDate.of(2016, 4, 13))).isEqualTo(28);
    }

    @Test
    void insuranceAge_약관제23조_끝수_5개월29일은_버림() {
        // 1990-01-03 → 2020-07-01 = 30년 5월 29일 → 30세
        assertThat(InsuranceAge.of(LocalDate.of(1990, 1, 3), LocalDate.of(2020, 7, 1))).isEqualTo(30);
    }

    @Test
    void insuranceAge_약관제23조_끝수_정확히_6개월0일은_올림() {
        // "6개월 이상"이므로 경계값 포함. 1990-01-01 → 2020-07-01 = 30년 6월 0일 → 31세
        assertThat(InsuranceAge.of(LocalDate.of(1990, 1, 1), LocalDate.of(2020, 7, 1))).isEqualTo(31);
    }

    @Test
    void insuranceAge_약관제23조_생일_당일은_만나이_그대로() {
        // 1990-10-26 → 2020-10-26 = 30년 0월 0일 → 30세
        assertThat(InsuranceAge.of(LocalDate.of(1990, 10, 26), LocalDate.of(2020, 10, 26))).isEqualTo(30);
    }

    @Test
    void insuranceAge_윤년_2월29일생_끝수_6개월0일은_올림() {
        // 2004-02-29 → 2026-08-29 = 22년 6월 0일 → 23세
        assertThat(InsuranceAge.of(LocalDate.of(2004, 2, 29), LocalDate.of(2026, 8, 29))).isEqualTo(23);
    }

    @Test
    void insuranceAge_윤년_2월29일생_끝수_6개월_미만은_버림() {
        // 2004-02-29 → 2026-08-28 = 22년 5월 30일 → 22세
        assertThat(InsuranceAge.of(LocalDate.of(2004, 2, 29), LocalDate.of(2026, 8, 28))).isEqualTo(22);
    }

    @Test
    void insuranceAge_생년월일이_계약일_이후면_예외() {
        assertThatThrownBy(() -> InsuranceAge.of(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 10, 26)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.BIRTH_DATE_IN_FUTURE));
    }
}
