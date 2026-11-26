package kr.tave.minicore.contract.service;

// 계약 상태 변경 서비스 통합 테스트.
// Testcontainers PostgreSQL에 Flyway V1~V9를 적용하고, 상태 변경과 이력 1줄이 함께 저장되는지,
// 허용되지 않은 변경은 상태·이력 모두 그대로인지, 사고일 당시 상태 조회가 맞는지 확인한다.

import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.domain.Actor;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@Testcontainers
class ContractStatusServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final LocalDate APPLIED = LocalDate.of(2026, 3, 10);
    private static final LocalDate PAID = LocalDate.of(2026, 3, 15);

    @Autowired
    ContractStatusService service;

    @Autowired
    JdbcTemplate jdbc;

    private long customerId;
    private long staffId;
    private long contractId;

    /** 테스트마다 회원·직원과 청약(PENDING) 계약 1건을 새로 만든다 */
    @BeforeEach
    void setUp() {
        long versionId = ensureProductVersion();
        customerId = insertUser("CUSTOMER");
        staffId = insertUser("ADMIN");
        contractId = jdbc.queryForObject("""
                INSERT INTO contract (user_id, version_id, sex, insurance_age, insured_amount, monthly_premium,
                                      pay_term_plan_type, pay_term_plan_value, pay_term_years, insure_term_code,
                                      applied_on, beneficiary_name, beneficiary_relation)
                VALUES (?, ?, 'M', 30, 50000000, 165350, 'FIXED_YEARS', 10, 10, '99', ?, '이수익', 'SPOUSE')
                RETURNING id
                """, Long.class, customerId, versionId, APPLIED);
        jdbc.update("""
                INSERT INTO contract_history (contract_id, from_status, to_status, reason, actor_type, actor_id, occurred_on)
                VALUES (?, NULL, 'PENDING', '가입 신청', 'CUSTOMER', ?, ?)
                """, contractId, customerId, APPLIED);
    }

    @Test
    void 첫_납부로_유지가_되고_계약일과_납입일이_정해진다() {
        Contract c = service.activate(contractId, PAID, Actor.customer(customerId));

        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, contract_date, billing_day FROM contract WHERE id = ?", contractId);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("contract_date").toString()).isEqualTo("2026-03-15");
        assertThat(((Number) row.get("billing_day")).intValue()).isEqualTo(15);
        assertThat(lastHistory()).containsEntry("from_status", "PENDING").containsEntry("to_status", "ACTIVE")
                .containsEntry("actor_type", "CUSTOMER");
    }

    @Test
    void 실효는_배치가_바꾸고_사람_id를_남기지_않는다() {
        service.activate(contractId, PAID, Actor.customer(customerId));

        service.lapse(contractId, LocalDate.of(2026, 6, 1), 1L);

        assertThat(status()).isEqualTo("LAPSED");
        Map<String, Object> h = lastHistory();
        assertThat(h).containsEntry("to_status", "LAPSED").containsEntry("actor_type", "BATCH");
        assertThat(h.get("actor_id")).isNull();
        assertThat(h.get("occurred_on").toString()).isEqualTo("2026-06-01");
    }

    @Test
    void 장해_승인과_사망_지급은_직원_id와_함께_기록된다() {
        service.activate(contractId, PAID, Actor.customer(customerId));

        service.waivePremium(contractId, LocalDate.of(2027, 1, 5), staffId, 11L);
        service.terminate(contractId, LocalDate.of(2030, 5, 2), staffId, 22L);

        assertThat(status()).isEqualTo("TERMINATED");
        assertThat(jdbc.queryForList(
                "SELECT to_status FROM contract_history WHERE contract_id = ? ORDER BY id", String.class, contractId))
                .containsExactly("PENDING", "ACTIVE", "PREMIUM_WAIVED", "TERMINATED");
        assertThat(lastHistory()).containsEntry("actor_type", "ADMIN").containsEntry("actor_id", staffId);
    }

    @Test
    void 허용되지_않은_변경은_409이고_상태와_이력이_그대로다() {
        // 청약 상태에서 바로 납입면제는 불가 (첫 보험료 전이라 보장 전)
        assertThatThrownBy(() -> service.waivePremium(contractId, PAID, staffId, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_CONTRACT_STATUS);

        assertThat(status()).isEqualTo("PENDING");
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void 실효된_계약은_사망_지급으로도_종료할_수_없다() {
        service.activate(contractId, PAID, Actor.customer(customerId));
        service.lapse(contractId, LocalDate.of(2026, 6, 1), 1L);

        assertThatThrownBy(() -> service.terminate(contractId, LocalDate.of(2026, 6, 2), staffId, 1L))
                .isInstanceOf(ApiException.class);
        assertThat(status()).isEqualTo("LAPSED");
    }

    @Test
    void 없는_계약은_404() {
        assertThatThrownBy(() -> service.lapse(-1L, PAID, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.CONTRACT_NOT_FOUND);
    }

    /** 발표 7장 시나리오: 3/15 납입기일 미납 → 5/31 유예 종료 → 6/1 실효 */
    @Test
    void 사고일_당시_상태로_보장_여부를_판단한다() {
        service.activate(contractId, PAID, Actor.customer(customerId));
        service.lapse(contractId, LocalDate.of(2026, 6, 1), 1L);

        assertThat(service.statusOn(contractId, LocalDate.of(2026, 3, 12))).contains(ContractStatus.PENDING);
        assertThat(service.statusOn(contractId, LocalDate.of(2026, 5, 20))).contains(ContractStatus.ACTIVE);
        assertThat(service.statusOn(contractId, LocalDate.of(2026, 6, 1))).contains(ContractStatus.LAPSED);
        assertThat(service.statusOn(contractId, LocalDate.of(2026, 6, 2))).contains(ContractStatus.LAPSED);
        assertThat(service.statusOn(contractId, LocalDate.of(2026, 3, 1))).isEmpty();
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM contract WHERE id = ?", String.class, contractId);
    }

    private int historyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM contract_history WHERE contract_id = ?", Integer.class, contractId);
    }

    private Map<String, Object> lastHistory() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT from_status, to_status, actor_type, actor_id, occurred_on
                FROM contract_history WHERE contract_id = ? ORDER BY id DESC LIMIT 1
                """, contractId);
        return rows.get(0);
    }

    private long ensureProductVersion() {
        jdbc.update("""
                INSERT INTO product (product_code, short_name, terms_code)
                VALUES ('T000002', '(테스트)계약상태', 'PT00002')
                ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO product_version (product_code, effective_from)
                VALUES ('T000002', DATE '2026-01-01')
                ON CONFLICT DO NOTHING
                """);
        return jdbc.queryForObject(
                "SELECT id FROM product_version WHERE product_code = 'T000002'", Long.class);
    }

    private long insertUser(String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', '테스트', DATE '1996-03-01', 'M', ?) RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local", role);
    }
}
