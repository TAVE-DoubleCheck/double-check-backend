package kr.tave.minicore.claim.api;

// 새 파일 (#35): 청구 심사·승인·거절 통합 테스트 (Testcontainers PostgreSQL + 실제 보안 설정).
//   계약: 3/15 성립, 유지 중(ACTIVE), 수익자 이수익(배우자). 청구는 접수 서비스로 만든 뒤 직원 API로 심사한다.
//   확인: 장해 승인 → 계약 납입면제, 사망 승인 → 지급건 PENDING, 점검 실패 409, 거절 후 재청구, 상태 오류, 동시 승인.

import kr.tave.minicore.auth.domain.Role;
import kr.tave.minicore.auth.security.JwtProvider;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimantRelation;
import kr.tave.minicore.claim.service.ClaimIntakeService;
import kr.tave.minicore.claim.service.ClaimReviewService;
import kr.tave.minicore.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@AutoConfigureMockMvc
@Testcontainers
class ClaimReviewIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final LocalDate ACCIDENT = LocalDate.of(2026, 5, 20);

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

    private LoginUser customer;
    private LoginUser staff;
    private long contractId;

    /** 테스트마다 고객·직원과 유지 중(ACTIVE) 계약 1건 */
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
    }

    /** 장해: 접수 → 심사 시작 → 판정 60 → 승인 = 청구 CLOSED + 계약 납입면제, 이력 4줄 */
    @Test
    void 장해_승인이면_청구_CLOSED_계약_납입면제() throws Exception {
        long claimId = disabilityClaim();
        post("/api/admin/claims/" + claimId + "/review", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWING"));
        assess(claimId, 60).andExpect(status().isCreated()).andExpect(jsonPath("$.disabilityRate").value(60));

        post("/api/admin/claims/" + claimId + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.contractStatus").value("PREMIUM_WAIVED"))
                .andExpect(jsonPath("$.decidedOn").value(today().toString()))
                .andExpect(jsonPath("$.payoutId").doesNotExist()); // 장해는 돈이 나가지 않음

        assertThat(jdbc.queryForObject("SELECT status FROM contract WHERE id = ?", String.class, contractId))
                .isEqualTo("PREMIUM_WAIVED");
        assertThat(jdbc.queryForList("SELECT to_status FROM claim_history WHERE claim_id = ? ORDER BY id",
                String.class, claimId)).containsExactly("RECEIVED", "REVIEWING", "APPROVED", "CLOSED");
        assertThat(payoutCount(claimId)).isZero();
    }

    /** 지급률 40 → 409 + 실패 항목·추천 거절 사유. 아무것도 안 바뀜. 다시 60으로 판정하면 최신 값으로 승인 */
    @Test
    void 지급률_50_미만이면_409_후_재판정하면_승인() throws Exception {
        long claimId = reviewingDisability();
        assess(claimId, 40);

        post("/api/admin/claims/" + claimId + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_CHECK_FAILED"))
                .andExpect(jsonPath("$.details.failedChecks[0]").value("DISABILITY_RATE_50"))
                .andExpect(jsonPath("$.details.suggestedRejectReasons[0]").value("DISABILITY_RATE_UNDER_50"));
        assertThat(claimStatus(claimId)).isEqualTo("REVIEWING");
        assertThat(jdbc.queryForObject("SELECT status FROM contract WHERE id = ?", String.class, contractId))
                .isEqualTo("ACTIVE");

        assess(claimId, 60); // 고치지 않고 새 기록 추가 → 최신 값이 현재 판정
        post("/api/admin/claims/" + claimId + "/approve", null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM claim_assessment WHERE claim_id = ?",
                Integer.class, claimId)).isEqualTo(2);
    }

    /** 판정 기록 없이 승인 → 점검 4 실패 */
    @Test
    void 판정_없이_장해_승인은_409() throws Exception {
        long claimId = reviewingDisability();
        post("/api/admin/claims/" + claimId + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.failedChecks[0]").value("DISABILITY_RATE_50"));
    }

    /** 사망 승인 → APPROVED + 지급건 PENDING(금액 = 가입금액). 계약은 지급 실행 때 종료되므로 아직 ACTIVE */
    @Test
    void 사망_승인이면_지급건_PENDING_생성() throws Exception {
        long claimId = reviewingDeath("이수익");

        post("/api/admin/claims/" + claimId + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.payoutAmount").value(10000000))
                .andExpect(jsonPath("$.payoutId").isNumber());

        assertThat(jdbc.queryForMap("SELECT status, amount, approved_on FROM payout WHERE claim_id = ?", claimId))
                .containsEntry("status", "PENDING").containsEntry("amount", 10000000L);
        assertThat(jdbc.queryForObject("SELECT status FROM contract WHERE id = ?", String.class, contractId))
                .isEqualTo("ACTIVE");
    }

    /** 사망: 청구인이 수익자와 다르면 점검 5 실패 */
    @Test
    void 수익자_불일치_사망_승인은_409() throws Exception {
        long claimId = reviewingDeath("박다른");
        post("/api/admin/claims/" + claimId + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.failedChecks[0]").value("BENEFICIARY_MATCH"));
        assertThat(payoutCount(claimId)).isZero();
    }

    /** 거절 → REJECTED + 사유. 거절되면 같은 종류로 다시 청구 가능 */
    @Test
    void 거절_후_재청구_가능() throws Exception {
        long claimId = reviewingDisability();
        post("/api/admin/claims/" + claimId + "/reject", """
                {"reason":"DISABILITY_RATE_UNDER_50","memo":"척추 같은 부위 → 높은 것 40%"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("DISABILITY_RATE_UNDER_50"))
                .andExpect(jsonPath("$.decidedOn").value(today().toString()));

        Claim again = intake.receiveDisability(customer, contractId, ACCIDENT, "재청구");
        assertThat(again.getId()).isNotEqualTo(claimId);
    }

    /** 상태가 맞지 않는 요청은 409 INVALID_CLAIM_STATUS */
    @Test
    void 상태가_맞지_않으면_409() throws Exception {
        long received = disabilityClaim();
        post("/api/admin/claims/" + received + "/approve", null)       // 심사 시작 전 승인
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATUS"));
        assess(received, 60).andExpect(status().isConflict());         // 심사 시작 전 판정

        post("/api/admin/claims/" + received + "/review", null).andExpect(status().isOk());
        post("/api/admin/claims/" + received + "/review", null)        // 심사 시작 두 번
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATUS"));

        long death = reviewingDeath("이수익");
        assess(death, 60).andExpect(status().isConflict())             // 사망 청구에 판정
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATUS"));
    }

    /** 없는 청구 404, 거절 사유 누락·없는 값 400, 고객은 직원 주소 403 */
    @Test
    void 기타_오류() throws Exception {
        post("/api/admin/claims/999999/review", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));

        long claimId = reviewingDisability();
        post("/api/admin/claims/" + claimId + "/reject", "{}").andExpect(status().isBadRequest());
        post("/api/admin/claims/" + claimId + "/reject", "{\"reason\":\"ABC\"}").andExpect(status().isBadRequest());
        assess(claimId, 101).andExpect(status().isBadRequest());

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/claims/" + claimId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(customer)))
                .andExpect(status().isForbidden());
    }

    /**
     * ★ 동시 승인: 직원 두 명이 같은 사망 청구를 동시에 승인해도 지급건은 1개.
     *   늦은 쪽은 @Version 충돌(API에서는 409 CONCURRENT_UPDATE) 또는 이미 승인됨(409 INVALID_CLAIM_STATUS).
     *   지급건 UNIQUE 위반(500)이 나지 않는 것이 핵심 (청구 변경을 먼저 flush).
     */
    @Test
    void 동시_승인은_1건만_성공하고_지급건은_1개() throws Exception {
        long claimId = reviewingDeath("이수익");
        long otherStaff = insertUser("ADMIN", "박직원");
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = List.of(approveTask(start, claimId, staff.id()),
                approveTask(start, claimId, otherStaff));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> t : tasks) {
            futures.add(pool.submit(t));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsOnlyOnce("OK");
        assertThat(outcomes).allMatch(o -> o.equals("OK") || o.equals("LOCK") || o.equals("INVALID_CLAIM_STATUS"));
        assertThat(payoutCount(claimId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM claim_history WHERE claim_id = ? AND to_status = 'APPROVED'",
                Integer.class, claimId)).isEqualTo(1);
    }

    // ── 도우미 ──

    private Callable<String> approveTask(CountDownLatch start, long claimId, long staffId) {
        return () -> {
            start.await();
            try {
                review.approve(claimId, staffId);
                return "OK";
            } catch (OptimisticLockingFailureException e) {
                return "LOCK";
            } catch (ApiException e) {
                return e.code().name();
            }
        };
    }

    private long disabilityClaim() {
        return intake.receiveDisability(customer, contractId, ACCIDENT, "작업 중 손목 절단").getId();
    }

    private long reviewingDisability() {
        long id = disabilityClaim();
        review.startReview(id, staff.id());
        return id;
    }

    private long reviewingDeath(String claimantName) {
        long id = intake.receiveDeath(staff, contractId, ACCIDENT, claimantName, ClaimantRelation.SPOUSE).getId();
        review.startReview(id, staff.id());
        return id;
    }

    private ResultActions assess(long claimId, int rate) throws Exception {
        return post("/api/admin/claims/" + claimId + "/assessments", """
                {"disabilityRate":%d,"basis":"별표3 8.팔 1) 한 팔의 손목 이상을 잃었을 때 (60)","reason":"장해진단서 확인"}
                """.formatted(rate));
    }

    /** 직원 토큰으로 POST (본문이 null이면 본문 없음) */
    private ResultActions post(String url, String json) throws Exception {
        var req = MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(staff));
        if (json != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(req);
    }

    private LocalDate today() {
        return jdbc.queryForObject("SELECT business_date FROM system_clock WHERE id = 1", LocalDate.class);
    }

    private String claimStatus(long claimId) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId);
    }

    private int payoutCount(long claimId) {
        return jdbc.queryForObject("SELECT count(*) FROM payout WHERE claim_id = ?", Integer.class, claimId);
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
                VALUES ('T000035', '(테스트)청구심사', 'PT00035') ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO product_version (product_code, effective_from)
                VALUES ('T000035', DATE '2026-01-01') ON CONFLICT DO NOTHING
                """);
        return jdbc.queryForObject("SELECT id FROM product_version WHERE product_code = 'T000035'", Long.class);
    }

    private long insertUser(String role, String name) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', ?, DATE '1996-03-01', 'M', ?) RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local", name, role);
    }
}
