package kr.tave.minicore.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiResponseParserTest {

    private final ApiResponseParser parser = new ApiResponseParser(new ObjectMapper());

    @Test
    void 실제_약관_응답() {
        var page = parser.parseTerms(Fixtures.read("terms_hanaro_real.json"));
        assertThat(page.items()).hasSize(8);
        assertThat(page.items().get(1).gdsNm()).isEqualTo("(무)하나로표준1종종신");
    }

    @Test
    void 실제_보장_응답은_페이지_정보와_숫자형_줄번호를_가진다() {
        var page = parser.parseCoverage(Fixtures.read("coverage_page1_real.json"));
        assertThat(page.items()).hasSize(20);
        assertThat(page.numOfRows()).isEqualTo(20);
        assertThat(page.pageNo()).isEqualTo(1);
        assertThat(page.items().get(1).lineNo()).isEqualTo(2);
    }

    @Test
    void 실제_보험료_응답() {
        var page = parser.parsePremium(Fixtures.read("premium_anjeonbelt_0W_real.json"));
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).ctrtDvsnNm()).isEqualTo("주계약");
    }

    @Test
    void item이_객체_1개이거나_비어_있어도_리스트로() {
        String single = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":"
                + "{\"GDS_NM\":\"x\",\"APLCN_BGNG_YMD\":\"20260101\",\"APLCN_END_YMD\":\"99991231\",\"STPL_URL_ADDR\":\"u?p=P1\"}}}}}";
        assertThat(parser.parseTerms(single).items()).hasSize(1);
        assertThat(parser.parseTerms(Fixtures.EMPTY_RESPONSE).items()).isEmpty();
    }

    @Test
    void XML_응답과_오류_코드는_예외() {
        assertThatThrownBy(() -> parser.parseTerms("<OpenAPI_ServiceResponse>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</OpenAPI_ServiceResponse>"))
                .hasMessageContaining("XML");
        assertThatThrownBy(() -> parser.parseTerms("{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"키 오류\"}}}"))
                .hasMessageContaining("resultCode=30");
    }
}
