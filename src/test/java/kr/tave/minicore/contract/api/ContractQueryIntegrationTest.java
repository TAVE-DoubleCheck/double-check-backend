package kr.tave.minicore.contract.api;

// 계약 조회 API 통합 테스트.
// 유지(ACTIVE) 계약 1건(1회차 납부 완료, 2회차 대기)과 다른 고객의 계약 1건을 DB에 직접 넣고,
// 목록·상세·청구서·이력이 맞는지, 남의 계약은 404인지, 직원은 볼 수 있는지 확인한다.

import kr.tave.minicore.auth.domain.Role;
import kr.tave.minicore.auth.security.JwtProvider;
import kr.tave.minicore.auth.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@AutoConfigureMockMvc
@Testcontainers
class ContractQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    private long me;
    private long other;
    private long myContract;
    private long othersContract;

    @BeforeEach
    void setUp() {
        long versionId = ensureProductVersion();
        me = insertUser();
        other = insertUser();

        // 내 계약: 3/10 신청 → 3/15 1회차 납부(계약 성립) → 4/15 2회차 대기
        myContract = insertContract(me, versionId, "ACTIVE", "DATE '2026-03-15'", "15");
        insertHistory(myContract, null, "PENDING", "가입 신청", "2026-03-10");
        insertHistory(myContract, "PENDING", "ACTIVE", "1회차 납부 (계약 성립·보장 시작)", "2026-03-15");
        jdbc.update("""
                INSERT INTO premium_bill (contract_id, installment_no, due_date, grace_end_date, amount, status, paid_on, paid_channel)
                VALUES (?, 1, DATE '2026-03-10', DATE '2026-04-09', 165350, 'PAID', DATE '2026-03-15', 'ONLINE'),
                       (?, 2, DATE '2026-04-15', DATE '2026-06-30', 165350, 'ISSUED', NULL, NULL)
                """, myContract, myContract);

        // 다른 고객의 청약
        othersContract = insertContract(other, versionId, "PENDING", "NULL", "NULL");
    }

    @Test
    void 목록은_내_계약만_상품명과_납입_회차와_함께() throws Exception {
        mvc.perform(as(me, Role.CUSTOMER, get("/api/contracts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].contractId").value(myContract))
                .andExpect(jsonPath("$[0].productCode").value("T000003"))
                .andExpect(jsonPath("$[0].productName").value("(테스트)조회"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].contractDate").value("2026-03-15"))
                .andExpect(jsonPath("$[0].paidInstallments").value(1))
                .andExpect(jsonPath("$[0].totalInstallments").value(120));
    }

    @Test
    void 상세는_스냅샷과_다음에_낼_청구서를_준다() throws Exception {
        mvc.perform(as(me, Role.CUSTOMER, get("/api/contracts/" + myContract)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractNo").isNotEmpty())
                .andExpect(jsonPath("$.monthlyPremium").value(165350))
                .andExpect(jsonPath("$.insuranceAge").value(30))
                .andExpect(jsonPath("$.sex").value("M"))
                .andExpect(jsonPath("$.payTermPlan.type").value("FIXED_YEARS"))
                .andExpect(jsonPath("$.payTermPlan.years").value(10))
                .andExpect(jsonPath("$.billingDay").value(15))
                .andExpect(jsonPath("$.beneficiaryRelation").value("SPOUSE"))
                .andExpect(jsonPath("$.nextBill.installmentNo").value(2))
                .andExpect(jsonPath("$.nextBill.dueDate").value("2026-04-15"))
                .andExpect(jsonPath("$.nextBill.status").value("ISSUED"));
    }

    @Test
    void 청구서와_이력은_순서대로() throws Exception {
        mvc.perform(as(me, Role.CUSTOMER, get("/api/contracts/" + myContract + "/bills")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].installmentNo").value(1))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].paidChannel").value("ONLINE"))
                .andExpect(jsonPath("$[1].installmentNo").value(2))
                .andExpect(jsonPath("$[1].paidOn").doesNotExist());

        mvc.perform(as(me, Role.CUSTOMER, get("/api/contracts/" + myContract + "/history")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].toStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[1].occurredOn").value("2026-03-15"))
                .andExpect(jsonPath("$[1].actorId").doesNotExist());
    }

    @Test
    void 남의_계약은_있어도_404() throws Exception {
        for (String path : new String[]{"", "/bills", "/history"}) {
            mvc.perform(as(me, Role.CUSTOMER, get("/api/contracts/" + othersContract + path)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
        }
        mvc.perform(as(me, Role.CUSTOMER, get("/api/contracts/999999")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 직원은_모든_계약을_볼_수_있다() throws Exception {
        mvc.perform(as(me, Role.ADMIN, get("/api/contracts/" + othersContract)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.contractDate").doesNotExist())
                .andExpect(jsonPath("$.nextBill").doesNotExist());
    }

    @Test
    void 로그인_없이는_401() throws Exception {
        mvc.perform(get("/api/contracts"))
                .andExpect(status().isUnauthorized());
    }

    private RequestBuilder as(long userId, Role role,
                              org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        String token = jwtProvider.issue(new LoginUser(userId, "u" + userId + "@test.local", role));
        return req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private long ensureProductVersion() {
        jdbc.update("""
                INSERT INTO product (product_code, short_name, terms_code)
                VALUES ('T000003', '(테스트)조회', 'PT00003')
                ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO product_version (product_code, effective_from)
                VALUES ('T000003', DATE '2026-01-01')
                ON CONFLICT DO NOTHING
                """);
        return jdbc.queryForObject("SELECT id FROM product_version WHERE product_code = 'T000003'", Long.class);
    }

    private long insertUser() {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', '테스트', DATE '1996-03-01', 'M', 'CUSTOMER') RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local");
    }

    /** contractDate·billingDay는 SQL 조각 그대로 (청약이면 "NULL") */
    private long insertContract(long userId, long versionId, String status, String contractDate, String billingDay) {
        return jdbc.queryForObject("""
                INSERT INTO contract (user_id, version_id, sex, insurance_age, insured_amount, monthly_premium,
                                      pay_term_plan_type, pay_term_plan_value, pay_term_years, insure_term_code,
                                      applied_on, contract_date, billing_day, status,
                                      beneficiary_name, beneficiary_relation)
                VALUES (?, ?, 'M', 30, 50000000, 165350, 'FIXED_YEARS', 10, 10, '99',
                        DATE '2026-03-10', %s, %s, ?, '이수익', 'SPOUSE')
                RETURNING id
                """.formatted(contractDate, billingDay), Long.class, userId, versionId, status);
    }

    private void insertHistory(long contractId, String from, String to, String reason, String on) {
        jdbc.update("""
                INSERT INTO contract_history (contract_id, from_status, to_status, reason, actor_type, actor_id, occurred_on)
                VALUES (?, ?, ?, ?, 'CUSTOMER', ?, CAST(? AS DATE))
                """, contractId, from, to, reason, me, on);
    }
}
