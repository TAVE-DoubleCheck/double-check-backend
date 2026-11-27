package kr.tave.minicore.claim.api;

// 새 파일 (#33): 청구 접수 통합 테스트 (Testcontainers PostgreSQL + 실제 보안 설정 + Flyway V1~V9).
//   계약 이력: 3/10 청약 → 3/15 계약 성립 → 6/1 실효 (방향 수정안 5-1 예시).
//   확인: 사고일 기준 판정(5/20 접수 / 6/2 409), 접수 이력, 본인 계약만, 중복, 직원 대리 접수, 동시 접수.

import kr.tave.minicore.auth.domain.Role;
import kr.tave.minicore.auth.security.JwtProvider;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.claim.domain.ClaimantRelation;
import kr.tave.minicore.claim.service.ClaimIntakeService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@AutoConfigureMockMvc
@Testcontainers
class ClaimIntakeIntegrationTest {

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

    private long customerId;
    private long staffId;
    private long contractId;

    /** 테스트마다 고객·직원과 "3/15 성립 → 6/1 실효" 계약 1건을 새로 만든다 */
    @BeforeEach
    void setUp() {
        // 업무 날짜를 최소 7/1로 (사고일 5/20~6/2보다 뒤여야 함, 시계는 앞으로만 움직임)
        jdbc.update("UPDATE system_clock SET business_date = DATE '2026-07-01' WHERE business_date < DATE '2026-07-01'");
        long versionId = ensureProductVersion();
        customerId = insertUser("CUSTOMER", "김고객");
        staffId = insertUser("ADMIN", "이직원");
        contractId = jdbc.queryForObject("""
                INSERT INTO contract (user_id, version_id, sex, insurance_age, insured_amount, monthly_premium,
                                      pay_term_plan_type, pay_term_plan_value, pay_term_years, insure_term_code,
                                      applied_on, contract_date, billing_day, status, beneficiary_name, beneficiary_relation)
                VALUES (?, ?, 'M', 30, 10000000, 18190, 'FIXED_YEARS', 30, 30, '99',
                        DATE '2026-03-10', DATE '2026-03-15', 15, 'LAPSED', '이수익', 'SPOUSE')
                RETURNING id
                """, Long.class, customerId, versionId);
        history(null, "PENDING", "2026-03-10");
        history("PENDING", "ACTIVE", "2026-03-15");
        history("ACTIVE", "LAPSED", "2026-06-01"); // 3/15 납입기일 미납 → 5/31 유예 끝 → 6/1 실효
    }

    /** 실효(6/1) 전 사고 → 접수되고 이력 1줄 (없음 → RECEIVED) */
    @Test
    void 실효_전_사고는_접수된다() throws Exception {
        disability(customerId, "2026-05-20")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.type").value("DISABILITY"))
                .andExpect(jsonPath("$.intakeChannel").value("CUSTOMER"))
                .andExpect(jsonPath("$.claimantName").value("김고객"))   // 청구인 = 본인 이름
                .andExpect(jsonPath("$.claimantRelation").value("SELF"))
                .andExpect(jsonPath("$.receivedOn").value(today().toString())); // 접수일 = 업무 날짜

        Map<String, Object> h = jdbc.queryForMap("""
                SELECT h.from_status, h.to_status, h.actor_id FROM claim_history h
                JOIN claim c ON c.id = h.claim_id WHERE c.contract_id = ?
                """, contractId);
        assertThat(h.get("from_status")).isNull();
        assertThat(h).containsEntry("to_status", "RECEIVED").containsEntry("actor_id", customerId);
    }

    /** 실효 후 사고 → 409 + 실패 항목. 지금 상태가 아니라 사고일 상태로 판단했다는 증거 */
    @Test
    void 실효_후_사고는_409_CLAIM_NOT_ACCEPTABLE() throws Exception {
        disability(customerId, "2026-06-02")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_ACCEPTABLE"))
                .andExpect(jsonPath("$.details.failedChecks[0]").value("COVERED_ON_ACCIDENT_DATE"));
        assertThat(claimCount()).isZero();
    }

    /** 실효 당일(6/1) 사고도 보장 안 됨 (해지 "전" 사고만 보상, 약관 제28조①) */
    @Test
    void 실효_당일_사고는_보장_안_됨() throws Exception {
        disability(customerId, "2026-06-01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.failedChecks[0]").value("COVERED_ON_ACCIDENT_DATE"));
    }

    /** 같은 종류 진행 중 청구가 있으면 두 번째는 409 NOT_DUPLICATE */
    @Test
    void 중복_장해_청구는_409() throws Exception {
        disability(customerId, "2026-05-20").andExpect(status().isCreated());

        disability(customerId, "2026-05-21")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.failedChecks[0]").value("NOT_DUPLICATE"));
        assertThat(claimCount()).isEqualTo(1);
    }

    /** 다른 고객이 내 계약으로 청구 → 있는지도 알려 주지 않고 404 */
    @Test
    void 남의_계약은_404() throws Exception {
        long other = insertUser("CUSTOMER", "박남");
        disability(other, "2026-05-20")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
    }

    /** 고객 주소로 사망 청구 → 400, 미래 사고일 → 400 */
    @Test
    void 입력_오류는_400() throws Exception {
        post(customerId, "/api/claims", """
                {"contractId":%d,"type":"DEATH","accidentDate":"2026-05-20","accidentDescription":"사고"}
                """.formatted(contractId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        disability(customerId, today().plusDays(1).toString()) // 업무 날짜 다음 날
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 직원 대리 사망 접수: 실효 전 사망 → 201 STAFF, 실효 후 사망 → 409 */
    @Test
    void 직원_사망_대리_접수() throws Exception {
        death(staffId, "2026-06-02").andExpect(status().isConflict());

        death(staffId, "2026-05-20")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEATH"))
                .andExpect(jsonPath("$.intakeChannel").value("STAFF"))
                .andExpect(jsonPath("$.claimantRelation").value("SPOUSE"));
    }

    /** 고객이 직원 주소로 사망 접수 → 403 (보안 설정, BE B) */
    @Test
    void 고객은_사망_대리_접수_불가() throws Exception {
        death(customerId, "2026-05-20").andExpect(status().isForbidden());
    }

    /**
     * ★ 동시 중복 접수: 같은 계약에 장해 청구 2건이 동시에 들어와도 1건만 저장된다.
     *   앞 단계 점검(존재 여부 조회)을 둘 다 통과해도 DB 부분 UNIQUE가 늦은 쪽을 막고, 그 오류는 409로 바뀐다.
     */
    @Test
    void 동시_중복_접수는_1건만_성공() throws Exception {
        LoginUser me = new LoginUser(customerId, "c@test.local", Role.CUSTOMER);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () -> {
            start.await(); // 두 스레드를 동시에 출발
            try {
                intake.receiveDisability(me, contractId, LocalDate.parse("2026-05-20"), "동시 사고");
                return "OK";
            } catch (ApiException e) {
                return e.code().name() + ":" + e.details();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>(List.of(pool.submit(task), pool.submit(task)));
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsOnlyOnce("OK");
        assertThat(outcomes).anyMatch(o -> o.startsWith(ErrorCode.CLAIM_NOT_ACCEPTABLE.name()) && o.contains("NOT_DUPLICATE"));
        assertThat(claimCount()).isEqualTo(1);
    }

    // ── 도우미 ──

    private ResultActions disability(long userId, String accidentDate) throws Exception {
        return post(userId, "/api/claims", """
                {"contractId":%d,"type":"DISABILITY","accidentDate":"%s","accidentDescription":"작업 중 손목 절단"}
                """.formatted(contractId, accidentDate));
    }

    private ResultActions death(long userId, String deathDate) throws Exception {
        return post(userId, "/api/admin/claims", """
                {"contractId":%d,"deathDate":"%s","claimantName":"이수익","claimantRelation":"SPOUSE"}
                """.formatted(contractId, deathDate));
    }

    /** 해당 회원 토큰으로 JSON POST */
    private ResultActions post(long userId, String url, String json) throws Exception {
        Role role = "ADMIN".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId))
                ? Role.ADMIN : Role.CUSTOMER;
        String token = jwt.issue(new LoginUser(userId, userId + "@test.local", role));
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void history(String from, String to, String on) {
        jdbc.update("""
                INSERT INTO contract_history (contract_id, from_status, to_status, reason, actor_type, actor_id, occurred_on)
                VALUES (?, ?, ?, '테스트', 'BATCH', NULL, ?::date)
                """, contractId, from, to, on);
    }

    /** 현재 업무 날짜 (system_clock). 앞으로만 움직이므로 실제 날짜보다 뒤일 수 있다 */
    private LocalDate today() {
        return jdbc.queryForObject("SELECT business_date FROM system_clock WHERE id = 1", LocalDate.class);
    }

    private int claimCount() {
        return jdbc.queryForObject("SELECT count(*) FROM claim WHERE contract_id = ?", Integer.class, contractId);
    }

    private long ensureProductVersion() {
        jdbc.update("""
                INSERT INTO product (product_code, short_name, terms_code)
                VALUES ('T000033', '(테스트)청구접수', 'PT00033') ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO product_version (product_code, effective_from)
                VALUES ('T000033', DATE '2026-01-01') ON CONFLICT DO NOTHING
                """);
        return jdbc.queryForObject("SELECT id FROM product_version WHERE product_code = 'T000033'", Long.class);
    }

    private long insertUser(String role, String name) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', ?, DATE '1996-03-01', 'M', ?) RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local", name, role);
    }
}
