package kr.tave.minicore.contract.api;

// 가입 신청 API 통합 테스트.
// 실제 상품 seed + 업무 날짜 2026-10-26 고정. 화면 흐름 그대로 "보험료 계산 → 그 결과로 가입 신청"을 하고
// 계약(PENDING)·이력·1회차 청구서가 함께 저장되는지, 값이 다르면 아무것도 저장되지 않는지 확인한다.

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.tave.minicore.auth.domain.Role;
import kr.tave.minicore.auth.security.JwtProvider;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.clock.ClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@AutoConfigureMockMvc
@Testcontainers
class ContractApplyIntegrationTest {

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
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    ObjectMapper objectMapper;

    private static boolean seeded = false;

    private long customerId;
    private String customerToken;

    @BeforeEach
    void setUp() throws Exception {
        if (!seeded) {
            postgres.copyFileToContainer(
                    MountableFile.forHostPath(Path.of("seed/product_seed.sql")), "/tmp/product_seed.sql");
            var result = postgres.execInContainer(
                    "psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                    "-v", "ON_ERROR_STOP=1", "-f", "/tmp/product_seed.sql");
            assertThat(result.getExitCode()).as("seed 복원 실패: %s", result.getStderr()).isZero();
            seeded = true;
        }
        // 1996-10-20생 남성 → 2026-10-26 기준 보험나이 30 (상품 테스트의 165,350원 예시와 같은 사람)
        customerId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', '김가입', DATE '1996-10-20', 'M', 'CUSTOMER') RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local");
        customerToken = token(customerId, Role.CUSTOMER);
    }

    @Test
    void 보험료_계산_결과로_가입하면_청약과_이력과_1회차_청구서가_생긴다() throws Exception {
        JsonNode quote = quote("payTermYears", "10");
        assertThat(quote.get("monthlyPremium").asLong()).isEqualTo(165_350L);

        mvc.perform(apply(customerToken, body(quote, "\"payTermYears\":10", 165_350L)))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.contractNo").value(org.hamcrest.Matchers.startsWith("C-")))
                .andExpect(jsonPath("$.productCode").value("4008400"))
                .andExpect(jsonPath("$.versionId").value(quote.get("versionId").asLong()))
                .andExpect(jsonPath("$.insuranceAge").value(30))
                .andExpect(jsonPath("$.monthlyPremium").value(165_350))
                .andExpect(jsonPath("$.payTermYears").value(10))
                .andExpect(jsonPath("$.appliedOn").value(BUSINESS_DATE))
                .andExpect(jsonPath("$.firstBill.installmentNo").value(1))
                .andExpect(jsonPath("$.firstBill.amount").value(165_350))
                .andExpect(jsonPath("$.firstBill.dueDate").value(BUSINESS_DATE))
                .andExpect(jsonPath("$.firstBill.payBy").value("2026-11-25"));

        Map<String, Object> c = jdbc.queryForMap("""
                SELECT id, status, contract_date, billing_day, monthly_premium, insurance_age,
                       pay_term_plan_type, pay_term_plan_value, pay_term_years, sex, beneficiary_relation
                FROM contract WHERE user_id = ?""", customerId);
        assertThat(c).containsEntry("status", "PENDING")
                .containsEntry("monthly_premium", 165_350L)
                .containsEntry("pay_term_plan_type", "FIXED_YEARS")
                .containsEntry("beneficiary_relation", "SPOUSE");
        assertThat(c.get("contract_date")).as("계약일은 첫 보험료 낼 때 정해진다").isNull();
        assertThat(c.get("billing_day")).isNull();
        long contractId = ((Number) c.get("id")).longValue();

        Map<String, Object> h = jdbc.queryForMap(
                "SELECT from_status, to_status, actor_type, actor_id, occurred_on FROM contract_history WHERE contract_id = ?",
                contractId);
        assertThat(h.get("from_status")).isNull();
        assertThat(h).containsEntry("to_status", "PENDING").containsEntry("actor_type", "CUSTOMER")
                .containsEntry("actor_id", customerId);
        assertThat(h.get("occurred_on").toString()).isEqualTo(BUSINESS_DATE);

        Map<String, Object> b = jdbc.queryForMap(
                "SELECT installment_no, status, amount, paid_on FROM premium_bill WHERE contract_id = ?", contractId);
        assertThat(((Number) b.get("installment_no")).intValue()).isEqualTo(1);
        assertThat(b).containsEntry("status", "ISSUED").containsEntry("amount", 165_350L);
        assertThat(b.get("paid_on")).isNull();
    }

    @Test
    void 팔십세납은_플랜과_변환된_납입년수를_둘_다_저장한다() throws Exception {
        JsonNode quote = quote("payTermPlan", "TO_AGE_80");
        long premium = quote.get("monthlyPremium").asLong();

        mvc.perform(apply(customerToken, body(quote, "\"payTermPlan\":\"TO_AGE_80\"", premium)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payTermYears").value(50))
                .andExpect(jsonPath("$.payTermPlan.type").value("TO_AGE"))
                .andExpect(jsonPath("$.payTermPlan.toAge").value(80));

        Map<String, Object> c = jdbc.queryForMap(
                "SELECT pay_term_plan_type, pay_term_plan_value, pay_term_years FROM contract WHERE user_id = ?",
                customerId);
        assertThat(c).containsEntry("pay_term_plan_type", "TO_AGE");
        assertThat(((Number) c.get("pay_term_plan_value")).intValue()).isEqualTo(80);
        assertThat(((Number) c.get("pay_term_years")).intValue()).isEqualTo(50);
    }

    @Test
    void 화면에서_본_보험료와_다르면_409이고_아무것도_저장되지_않는다() throws Exception {
        JsonNode quote = quote("payTermYears", "10");

        mvc.perform(apply(customerToken, body(quote, "\"payTermYears\":10", 150_000L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));

        assertThat(contractCount()).isZero();
    }

    @Test
    void 납입기간을_둘_다_보내거나_안_보내면_400() throws Exception {
        JsonNode quote = quote("payTermYears", "10");

        mvc.perform(apply(customerToken, body(quote, "\"payTermYears\":10,\"payTermPlan\":\"TO_AGE_80\"", 165_350L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(apply(customerToken, body(quote, "\"payTermYears\":null", 165_350L)))
                .andExpect(status().isBadRequest());
        assertThat(contractCount()).isZero();
    }

    @Test
    void 비종신_상품은_가입할_수_없다() throws Exception {
        String json = """
                {"productCode":"4008700","insureTermCode":"99","amount":50000000,"payTermYears":10,
                 "beneficiaryName":"이수익","beneficiaryRelation":"SPOUSE",
                 "expectedVersionId":1,"expectedMonthlyPremium":1}
                """;
        mvc.perform(apply(customerToken, json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_SUBSCRIBABLE"));
    }

    @Test
    void 로그인_없이는_401_직원은_403() throws Exception {
        JsonNode quote = quote("payTermYears", "10");
        String json = body(quote, "\"payTermYears\":10", 165_350L);

        mvc.perform(post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
        mvc.perform(apply(token(customerId, Role.ADMIN), json))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(contractCount()).isZero();
    }

    private JsonNode quote(String planParam, String planValue) throws Exception {
        String res = mvc.perform(get("/api/products/4008400/quote")
                        .param("birthDate", "1996-10-20").param("sex", "M")
                        .param(planParam, planValue).param("insureTermCode", "99")
                        .param("amount", "50000000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res);
    }

    /** 보험료 계산 응답 + 납입기간 조각으로 가입 요청 본문을 만든다 */
    private static String body(JsonNode quote, String payTermPart, long expectedPremium) {
        return """
                {"productCode":"4008400","insureTermCode":"99","amount":50000000,%s,
                 "beneficiaryName":"이수익","beneficiaryRelation":"SPOUSE",
                 "expectedVersionId":%d,"expectedMonthlyPremium":%d}
                """.formatted(payTermPart, quote.get("versionId").asLong(), expectedPremium);
    }

    private static org.springframework.test.web.servlet.RequestBuilder apply(String token, String json) {
        return post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content(json)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String token(long userId, Role role) {
        return jwtProvider.issue(new LoginUser(userId, "u" + userId + "@test.local", role));
    }

    private int contractCount() {
        return jdbc.queryForObject("SELECT count(*) FROM contract WHERE user_id = ?", Integer.class, customerId);
    }
}
