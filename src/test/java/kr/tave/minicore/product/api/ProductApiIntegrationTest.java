package kr.tave.minicore.product.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.product.repository.ProductVersionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 API 3개를 실제 적재본 덤프(seed) 위에서 검증한다.
 * 업무 날짜는 2026-10-26 고정 → 1996-10-20생 = 보험나이 30세 (만 30년 0월 6일, 끝수 버림).
 * 컨텍스트 기동 자체가 Hibernate ddl-auto=validate 통과(엔티티 매핑 = 실제 스키마)를 검증한다.
 */
@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@AutoConfigureMockMvc
@Testcontainers
class ProductApiIntegrationTest {

    private static final String BUSINESS_DATE = "2026-10-26";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        ClockService fixedClockService() {
            return () -> LocalDate.parse(BUSINESS_DATE);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductVersionRepository versions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private static boolean seeded = false;

    @BeforeEach
    void loadSeed() throws Exception {
        if (seeded) {
            return;
        }
        postgres.copyFileToContainer(
                MountableFile.forHostPath(Path.of("seed/product_seed.sql")), "/tmp/product_seed.sql");
        var result = postgres.execInContainer(
                "psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                "-v", "ON_ERROR_STOP=1", "-f", "/tmp/product_seed.sql");
        assertThat(result.getExitCode()).as("seed 복원 실패: %s", result.getStderr()).isZero();
        seeded = true;
    }

    @Test
    void 목록_8건_종신4종만_subscribable_비종신은_TERM() throws Exception {
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[?(@.subscribable == true)]", hasSize(4)))
                .andExpect(jsonPath("$[?(@.productCode == '4008300')].subscribable").value(true))
                .andExpect(jsonPath("$[?(@.productCode == '4008600')].subscribable").value(true))
                .andExpect(jsonPath("$[?(@.productCode == '4008400')].insureType").value("WHOLE_LIFE"))
                .andExpect(jsonPath("$[?(@.productCode == '4008700')].subscribable").value(false))
                .andExpect(jsonPath("$[?(@.productCode == '4008700')].insureType").value("TERM"));
    }

    @Test
    void 상세_4008400_약관코드와_보장문단과_가입나이범위() throws Exception {
        mvc.perform(get("/api/products/4008400"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsCode").value("P400084"))
                .andExpect(jsonPath("$.insureType").value("WHOLE_LIFE"))
                .andExpect(jsonPath("$.unitAmountWon").value(10_000_000))
                .andExpect(jsonPath("$.joinAgeRange.M.min").value(15))
                .andExpect(jsonPath("$.joinAgeRange.M.max").value(70))
                .andExpect(jsonPath("$.joinAgeRange.W.min").value(15))
                .andExpect(jsonPath("$.coverages", hasSize(2)))
                .andExpect(jsonPath("$.coverages[0].benefitName").value("사망보험금"))
                .andExpect(jsonPath("$.coverages[1].isNote").value(true));
    }

    @Test
    void quote_정상_보험나이30_남_10년납_종신_5천만원은_165350원() throws Exception {
        mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param("payTermYears", "10").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insuranceAge").value(30))
                .andExpect(jsonPath("$.monthlyPremium").value(165_350))
                .andExpect(jsonPath("$.premiumWonPerUnit").value(33_070))
                .andExpect(jsonPath("$.businessDate").value(BUSINESS_DATE))
                .andExpect(jsonPath("$.assumptions", hasSize(2)));
    }

    @Test
    void quote의_versionId는_오늘_유효_버전의_id다_가입시_버전고정_근거() throws Exception {
        long effectiveVersionId = versions.findEffective("4008400", LocalDate.parse(BUSINESS_DATE))
                .orElseThrow().getId();

        mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param("payTermYears", "10").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(effectiveVersionId));
    }

    @Test
    void quote_같은_입력_두_번이면_같은_결과_멱등() throws Exception {
        String first = quoteBody();
        String second = quoteBody();
        assertThat(second).isEqualTo(first);
    }

    private String quoteBody() throws Exception {
        return mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param("payTermYears", "10").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 실패_비종신_상품은_409_PRODUCT_NOT_SUBSCRIBABLE() throws Exception {
        mvc.perform(get("/api/products/4008700/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param("payTermYears", "10").param("insureTermCode", "60")
                        .param("amount", "50000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_SUBSCRIBABLE"));
    }

    @Test
    void 실패_보험나이80은_409_RATE_NOT_FOUND_가입범위_힌트포함() throws Exception {
        mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1946-10-20").param("sex", "M")
                        .param("payTermYears", "10").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message", containsString("80세")))
                .andExpect(jsonPath("$.message", containsString("15~70")));
    }

    @Test
    void 실패_가입금액_1500만원은_400_AMOUNT_NOT_UNIT_MULTIPLE() throws Exception {
        mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param("payTermYears", "10").param("insureTermCode", "99")
                        .param("amount", "15000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AMOUNT_NOT_UNIT_MULTIPLE"));
    }

    @Test
    void 실패_없는_상품코드는_404_PRODUCT_NOT_FOUND() throws Exception {
        mvc.perform(get("/api/products/9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void TO_AGE_80_보험나이30이면_납입년수_50으로_변환() throws Exception {
        mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param("payTermPlan", "TO_AGE_80").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insuranceAge").value(30))
                .andExpect(jsonPath("$.payTermYears").value(50))
                .andExpect(jsonPath("$.payTermPlan.type").value("TO_AGE"))
                .andExpect(jsonPath("$.payTermPlan.toAge").value(80));
    }

    @Test
    void TO_AGE_80_보험나이65는_15년납과_같은_보험료() throws Exception {
        assertThat(monthlyPremium("4008400", "1961-10-20", "payTermPlan", "TO_AGE_80"))     // 보험나이 65
                .isEqualTo(monthlyPremium("4008400", "1961-10-20", "payTermYears", "15"));
    }

    @Test
    void TO_AGE_80_보험나이70은_10년납과_같은_보험료() throws Exception {
        assertThat(monthlyPremium("4008400", "1956-10-20", "payTermPlan", "TO_AGE_80"))     // 보험나이 70
                .isEqualTo(monthlyPremium("4008400", "1956-10-20", "payTermYears", "10"));
    }

    private long monthlyPremium(String code, String birthDate, String payParam, String payValue) throws Exception {
        String body = mvc.perform(get("/api/products/" + code + "/quote")
                        .param("birthDate", birthDate).param("sex", "M")
                        .param(payParam, payValue).param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("monthlyPremium").asLong();
    }

    @Test
    void TO_AGE_80_보험나이80은_요율조회_전에_RATE_NOT_FOUND() throws Exception {
        mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1946-10-20").param("sex", "M")     // 보험나이 80
                        .param("payTermPlan", "TO_AGE_80").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("80세납은 80세 미만만 가입 가능"));
    }

    @Test
    void TO_AGE_80_2종_남성_보험나이67은_RATE_NOT_FOUND() throws Exception {
        // 2종 남성 최고 가입나이 66세 → 67세는 요율 없음 (80 − 67 = 13년납 변환까지는 정상)
        mvc.perform(get("/api/products/4008500/quote")
                        .param("birthDate", "1959-10-20").param("sex", "M")     // 보험나이 67
                        .param("payTermPlan", "TO_AGE_80").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message", containsString("20~66")));
    }

    @Test
    void 납입기간_파라미터_둘다_보내거나_둘다_없으면_400() throws Exception {
        mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param("payTermYears", "10").param("payTermPlan", "TO_AGE_80")
                        .param("insureTermCode", "99").param("amount", "50000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param("insureTermCode", "99").param("amount", "50000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 상세_플랜별_나이범위는_요율표_MIN_MAX와_일치() throws Exception {
        for (String code : java.util.List.of("4008400", "4008500", "4008700")) {
            String body = mvc.perform(get("/api/products/" + code))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode root = objectMapper.readTree(body);
            long versionId = root.path("version").path("versionId").asLong();
            JsonNode plans = root.path("payTermPlans");
            assertThat(plans.size()).as("%s 플랜 수", code).isGreaterThan(1);

            for (JsonNode plan : plans) {
                String condition = "FIXED_YEARS".equals(plan.path("type").asText())
                        ? "pay_term_years = " + plan.path("years").asInt()
                        : "join_age + pay_term_years = " + plan.path("toAge").asInt();
                for (String sex : java.util.List.of("M", "W")) {
                    var expected = jdbc.queryForMap(
                            "SELECT min(join_age) mn, max(join_age) mx FROM premium_rate"
                                    + " WHERE version_id = ? AND sex = ? AND " + condition, versionId, sex);
                    JsonNode range = plan.path("joinAgeRange").path(sex);
                    assertThat(range.path("min").asInt())
                            .as("%s %s %s min", code, plan, sex).isEqualTo(((Number) expected.get("mn")).intValue());
                    assertThat(range.path("max").asInt())
                            .as("%s %s %s max", code, plan, sex).isEqualTo(((Number) expected.get("mx")).intValue());
                }
            }
        }
    }

    @Test
    void 실패_2종은_보험나이19세가_하한_미달_메시지에_20_70() throws Exception {
        // 2007-10-20생 → 2026-10-26 기준 만 19년 0월 6일 → 보험나이 19세. 2종(간편)은 20세부터.
        // 여성 기준: 2종 범위는 성별로 달라서 남 20~66, 여 20~70이다 (실데이터 확인).
        mvc.perform(get("/api/products/4008500/quote")
                        .param("birthDate", "2007-10-20").param("sex", "W")
                        .param("payTermYears", "10").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message", containsString("20~70")));
    }

    @Test
    void 실패_2종_남성_상한은_66세_힌트도_성별_기준() throws Exception {
        // 새로 확인된 사실: 2종 남성 최고 가입나이는 66세 (여성 70세와 다름)
        mvc.perform(get("/api/products/4008500/quote")
                        .param("birthDate", "1958-10-20").param("sex", "M")   // 보험나이 68세
                        .param("payTermYears", "10").param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message", containsString("20~66")));
    }
}
