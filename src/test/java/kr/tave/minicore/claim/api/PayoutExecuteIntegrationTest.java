package kr.tave.minicore.claim.api;

// 새 파일 (#38): 지급 실행 통합 테스트 (Testcontainers PostgreSQL + Flyway V1~V9 + 실제 보안 설정).
//   준비: 유지 중 계약 → 사망 청구 접수 → 심사 → 승인 = 지급건 PENDING 1,000만 원.
//   확인: 지급 → 지급건·청구 PAID + 장부 1줄 + 계약 TERMINATED, 두 번째 409, 동시 지급 1건,
//         장부를 먼저 쓰면 V9 트리거가 거부(순서가 필요한 이유).

import kr.tave.minicore.auth.domain.Role;
import kr.tave.minicore.auth.security.JwtProvider;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.claim.domain.ClaimantRelation;
import kr.tave.minicore.claim.service.ClaimIntakeService;
import kr.tave.minicore.claim.service.ClaimReviewService;
import kr.tave.minicore.claim.service.PayoutService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@AutoConfigureMockMvc
@Testcontainers
class PayoutExecuteIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwt;

    @Autowired
    ClaimIntakeService intake;

    @Autowired
    ClaimReviewService review;

    @Autowired
    PayoutService payoutService;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    TransactionTemplate tx;

    private LoginUser customer;
    private LoginUser staff;
    private long contractId;
    private long claimId;
    private long payoutId;

    /** 테스트마다 사망 청구를 승인까지 진행 → 지급건 PENDING 1건 */
    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE system_clock SET business_date = DATE '2026-07-01' WHERE business_date < DATE '2026-07-01'");
        long versionId = ensureProductVersion();
        customer = new LoginUser(insertUser("CUSTOMER", "김고객"), "c@test.local", Role.CUSTOMER);
        staff = new LoginUser(insertUser("ADMIN", "이직원"), "s@test.local", Role.ADMIN);
        contractId = jdbc.queryForObject("""
                INSERT INTO contract (user_id, version_id, sex, insurance_age, insured_amount, monthly_premium,
                                      pay_term_plan_type, pay_term_plan_value, pay_term_years, insure_term_code,
                                      applied_on, contract_date, billing_day, status, beneficiary_name, beneficiary_relation)
                VALUES (?, ?, 'M', 40, 10000000, 18190, 'FIXED_YEARS', 30, 30, '99',
                        DATE '2026-03-10', DATE '2026-03-15', 15, 'ACTIVE', '이수익', 'SPOUSE')
                RETURNING id
                """, Long.class, customer.id(), versionId);
        contractHistory(null, "PENDING", "2026-03-10");
        contractHistory("PENDING", "ACTIVE", "2026-03-15");

        claimId = intake.receiveDeath(staff, contractId, LocalDate.of(2026, 5, 20), "이수익",
                ClaimantRelation.SPOUSE).getId();
        review.startReview(claimId, staff.id());
        payoutId = review.approve(claimId, staff.id()).payout().getId();
    }

    /** 지급 → 지급건·청구 PAID, 장부 CLAIM_OUT 1줄(금액·날짜 일치), 계약 TERMINATED, 이력 */
    @Test
    void 지급하면_장부_1줄과_계약_종료() throws Exception {
        LocalDate today = today();
        execute(staff).andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutId").value(payoutId))
                .andExpect(jsonPath("$.amount").value(10000000))
                .andExpect(jsonPath("$.paidOn").value(today.toString()))
                .andExpect(jsonPath("$.ledgerEntryId").isNumber())
                .andExpect(jsonPath("$.contractStatus").value("TERMINATED"));

        assertThat(jdbc.queryForMap("SELECT status, paid_on, paid_by FROM payout WHERE id = ?", payoutId))
                .containsEntry("status", "PAID").containsEntry("paid_by", staff.id());
        assertThat(jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId))
                .isEqualTo("PAID");
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT contract_id, type, amount, occurred_on FROM ledger WHERE payout_id = ?", payoutId);
        assertThat(ledger).containsEntry("contract_id", contractId).containsEntry("type", "CLAIM_OUT")
                .containsEntry("amount", 10000000L);                        // 장부 금액 = 지급액 = 가입금액
        assertThat(ledger.get("occurred_on").toString()).isEqualTo(today.toString()); // 장부 날짜 = 지급일
        assertThat(jdbc.queryForObject("SELECT status FROM contract WHERE id = ?", String.class, contractId))
                .isEqualTo("TERMINATED");
        assertThat(jdbc.queryForList("SELECT to_status FROM claim_history WHERE claim_id = ? ORDER BY id",
                String.class, claimId)).containsExactly("RECEIVED", "REVIEWING", "APPROVED", "PAID");
    }

    /** 두 번째 지급 → 409 PAYOUT_ALREADY_PAID, 장부는 그대로 1줄 */
    @Test
    void 두_번째_지급은_409() throws Exception {
        execute(staff).andExpect(status().isOk());
        execute(staff).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYOUT_ALREADY_PAID"))
                .andExpect(jsonPath("$.message").value("다른 직원이 이미 지급했습니다"));
        assertThat(ledgerCount()).isEqualTo(1);
    }

    /** 없는 지급건 404, 고객 403 */
    @Test
    void 없는_지급건_404_고객_403() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/admin/payouts/999999/execute")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(staff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYOUT_NOT_FOUND"));
        execute(customer).andExpect(status().isForbidden());
        assertThat(ledgerCount()).isZero();
    }

    /**
     * ★ 동시 지급: 직원 두 명이 같은 지급건을 동시에 실행해도 돈은 한 번만 나간다.
     *   행 잠금으로 줄을 세우고, 늦은 쪽은 PAID를 보고 409. 장부 1줄, 계약 종료 이력 1줄.
     */
    @Test
    void 동시_지급은_1건만_성공() throws Exception {
        long otherStaff = insertUser("ADMIN", "박직원");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        for (long who : List.of(staff.id(), otherStaff)) {
            Callable<String> task = () -> {
                start.await();
                try {
                    payoutService.execute(payoutId, who);
                    return "OK";
                } catch (ApiException e) {
                    return e.code().name();
                }
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsExactlyInAnyOrder("OK", "PAYOUT_ALREADY_PAID");
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM contract_history WHERE contract_id = ? AND to_status = 'TERMINATED'",
                Integer.class, contractId)).isEqualTo(1);
    }

    /** 순서가 필요한 이유: 지급건이 PENDING인데 장부를 먼저 쓰면 V9 트리거가 거부한다 */
    @Test
    void 장부를_먼저_쓰면_V9가_거부() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledgerService.recordClaimPayout(payoutId)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ledger source mismatch");
        assertThat(ledgerCount()).isZero();
    }

    // ── 도우미 ──

    private ResultActions execute(LoginUser who) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/admin/payouts/" + payoutId + "/execute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(who)));
    }

    private int ledgerCount() {
        return jdbc.queryForObject("SELECT count(*) FROM ledger WHERE payout_id = ?", Integer.class, payoutId);
    }

    private LocalDate today() {
        return jdbc.queryForObject("SELECT business_date FROM system_clock WHERE id = 1", LocalDate.class);
    }

    private void contractHistory(String from, String to, String on) {
        jdbc.update("""
                INSERT INTO contract_history (contract_id, from_status, to_status, reason, actor_type, actor_id, occurred_on)
                VALUES (?, ?, ?, '테스트', 'BATCH', NULL, ?::date)
                """, contractId, from, to, on);
    }

    private long ensureProductVersion() {
        jdbc.update("""
                INSERT INTO product (product_code, short_name, terms_code)
                VALUES ('T000038', '(테스트)지급', 'PT00038') ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO product_version (product_code, effective_from)
                VALUES ('T000038', DATE '2026-01-01') ON CONFLICT DO NOTHING
                """);
        return jdbc.queryForObject("SELECT id FROM product_version WHERE product_code = 'T000038'", Long.class);
    }

    private long insertUser(String role, String name) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', ?, DATE '1996-03-01', 'M', ?) RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local", name, role);
    }
}
