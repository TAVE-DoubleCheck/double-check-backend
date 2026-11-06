package kr.tave.minicore.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizerTest {

    @Test
    void 보험료와_기준단위금액을_원_단위_정수로() {
        assertThat(Normalizer.parseWon("33070원")).isEqualTo(33_070L);
        assertThat(Normalizer.parseUnitAmount("1000만원")).isEqualTo(10_000_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"33,070", "1억원", "", "-5원", "0원"})
    void 관찰되지_않은_금액_형식은_추측하지_않고_거부(String raw) {
        assertThatThrownBy(() -> Normalizer.parseWon(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 납입기간_N년과_전기납() {
        assertThat(Normalizer.payTermYears("10년", "99")).isEqualTo(10);
        assertThat(Normalizer.payTermYears("전기납", "20")).isEqualTo(20);   // 실제 샘플: (무)안전벨트
        assertThat(Normalizer.isFullTermPay("전기납")).isTrue();
        assertThatThrownBy(() -> Normalizer.payTermYears("10세납", "99")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 적용종료일_99991231은_판매중_null() {
        assertThat(Normalizer.parseYmd("20260916")).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(Normalizer.parseEndYmd("99991231")).isNull();
    }

    @Test
    void 약관코드는_URL의_p_파라미터에서_추출() {
        assertThat(Normalizer.termsCodeFromUrl("https://m.epostlife.go.kr/terms.do?p=P400083&d=20261027"))
                .isEqualTo("P400083");
    }

    @Test
    void 급부명_기호_제거() {
        assertThat(Normalizer.benefitName("■ 사망보험금")).isEqualTo("사망보험금");
    }
}
