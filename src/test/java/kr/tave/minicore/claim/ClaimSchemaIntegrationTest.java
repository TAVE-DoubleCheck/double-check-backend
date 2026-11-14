package kr.tave.minicore.claim;

// V5(청구·심사·서류·지급) 스키마의 DB 규칙 통합 테스트.
// Testcontainers PostgreSQL에 Flyway V1~V5를 적용한 뒤, 중복 지급·중복 청구 방지와 추가만 하는 기록 등
// DB가 최후 방어선으로 막아야 하는 규칙이 실제로 막히는지 확인한다. (애플리케이션 코드 없이 SQL로 검증)

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@Testcontainers
class ClaimSchemaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    private long staffId;
    private long contractId;

    /** 테스트마다 직원 1명과 새 계약 1건을 만든다 (계약이 달라서 테스트끼리 중복 규칙이 섞이지 않음). */
    @BeforeEach
    void setUp() {
        long versionId = ensureProductVersion();
        long customerId = insertUser("CUSTOMER");
        staffId = insertUser("ADMIN");
        contractId = jdbc.queryForObject("""
                INSERT INTO contract (user_id, version_id, sex, insurance_age, insured_amount, monthly_premium,
                                      pay_term_plan_type, pay_term_plan_value, pay_term_years, insure_term_code,
                                      applied_on, contract_date, billing_day, status,
                                      beneficiary_name, beneficiary_relation)
                VALUES (?, ?, 'M', 30, 50000000, 165350, 'FIXED_YEARS', 10, 10, '99',
                        DATE '2026-11-01', DATE '2026-11-01', 1, 'ACTIVE', '이수익', 'SPOUSE')
                RETURNING id
                """, Long.class, customerId, versionId);
    }

    /** 같은 계약에 거절되지 않은 사망 청구는 1건만 들어간다 (자동 점검 6의 DB 방어선). */
    @Test
    void 같은_계약의_같은_종류_청구는_하나만_접수된다() {
        insertDeathClaim();

        assertThatThrownBy(this::insertDeathClaim)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_claim_active");
    }

    /** 거절된 청구는 자리를 비워 주므로 같은 종류로 다시 청구할 수 있다. */
    @Test
    void 거절된_청구는_다시_청구할_수_있다() {
        long claimId = insertDeathClaim();
        jdbc.update("""
                UPDATE claim SET status = 'REJECTED', reject_reason = 'BENEFICIARY_MISMATCH',
                                 decided_on = DATE '2027-06-23'
                WHERE id = ?
                """, claimId);

        long again = insertDeathClaim();

        assertThat(again).isNotEqualTo(claimId);
    }

    /** 청구 1건에 지급건은 1개만 (연결 규칙 2, 중복 지급 1차 DB 방어선). */
    @Test
    void 청구_하나에_지급건은_하나만_만들어진다() {
        long claimId = insertDeathClaim();
        insertPayout(claimId);

        assertThatThrownBy(() -> insertPayout(claimId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_payout_claim");
    }

    /** 지급 완료(PAID)로 바꾸려면 지급일·지급자가 같이 있어야 하고, 있으면 통과한다. */
    @Test
    void 지급_완료에는_지급일과_지급자가_필요하다() {
        long payoutId = insertPayout(insertDeathClaim());

        assertThatThrownBy(() -> jdbc.update("UPDATE payout SET status = 'PAID' WHERE id = ?", payoutId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_payout_paid");

        jdbc.update("UPDATE payout SET status = 'PAID', paid_on = DATE '2027-06-26', paid_by = ? WHERE id = ?",
                staffId, payoutId);
        assertThat(jdbc.queryForObject("SELECT status FROM payout WHERE id = ?", String.class, payoutId))
                .isEqualTo("PAID");
    }

    /** 상태 변경 이력은 추가만 된다. 수정·삭제는 트리거가 막는다. */
    @Test
    void 청구_이력은_수정도_삭제도_안_된다() {
        long claimId = insertDeathClaim();
        long historyId = jdbc.queryForObject("""
                INSERT INTO claim_history (claim_id, to_status, actor_id, occurred_on)
                VALUES (?, 'RECEIVED', ?, DATE '2027-06-22') RETURNING id
                """, Long.class, claimId, staffId);

        assertThatThrownBy(() -> jdbc.update("UPDATE claim_history SET reason = 'x' WHERE id = ?", historyId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM claim_history WHERE id = ?", historyId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    /** 판정 기록도 추가만 되고, 지급률은 0~100만 받는다. */
    @Test
    void 판정_기록은_수정이_안_되고_지급률은_0에서_100() {
        long claimId = insertDisabilityClaim();
        long assessmentId = insertAssessment(claimId, 60);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE claim_assessment SET disability_rate = 70 WHERE id = ?", assessmentId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> insertAssessment(claimId, 101))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_claim_assessment_rate");
    }

    /** 사망 청구는 직원 대리 접수만 된다 (회의 B-6). */
    @Test
    void 사망_청구는_고객_경로로_접수할_수_없다() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO claim (contract_id, type, accident_date, claimant_name, claimant_relation,
                                   intake_channel, created_by, received_on)
                VALUES (?, 'DEATH', DATE '2027-06-20', '이수익', 'SPOUSE', 'CUSTOMER', ?, DATE '2027-06-22')
                """, contractId, staffId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_claim_channel");
    }

    /** 장해 청구의 청구인은 본인(SELF)이어야 한다. */
    @Test
    void 장해_청구의_청구인은_본인이다() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO claim (contract_id, type, accident_date, accident_description, claimant_name,
                                   claimant_relation, intake_channel, created_by, received_on)
                VALUES (?, 'DISABILITY', DATE '2027-03-02', '사고', '이수익', 'SPOUSE', 'CUSTOMER', ?,
                        DATE '2027-03-10')
                """, contractId, staffId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_claim_relation");
    }

    /** 서류 「확인함」은 확인자·확인 시각이 같이 있어야 하고, 서류를 지우면 PDF 내용도 같이 지워진다. */
    @Test
    void 서류_확인에는_확인자가_필요하고_삭제하면_내용도_지워진다() {
        long claimId = insertDisabilityClaim();
        long documentId = jdbc.queryForObject("""
                INSERT INTO claim_document (claim_id, doc_type, file_name, content_type, size_bytes, uploaded_by)
                VALUES (?, 'DIAGNOSIS', '장해진단서.pdf', 'application/pdf', 9, ?) RETURNING id
                """, Long.class, claimId, staffId);
        jdbc.update("INSERT INTO claim_document_content (document_id, content) VALUES (?, ?)",
                documentId, "%PDF-1.7x".getBytes());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE claim_document SET is_verified = TRUE WHERE id = ?", documentId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_claim_document_verified");

        jdbc.update("DELETE FROM claim_document WHERE id = ?", documentId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM claim_document_content WHERE document_id = ?", Integer.class, documentId))
                .isZero();
    }

    /** 빈 파일은 저장할 수 없다. */
    @Test
    void 빈_파일_내용은_저장할_수_없다() {
        long claimId = insertDisabilityClaim();
        long documentId = jdbc.queryForObject("""
                INSERT INTO claim_document (claim_id, doc_type, file_name, content_type, size_bytes, uploaded_by)
                VALUES (?, 'CLAIM_FORM', '청구서.pdf', 'application/pdf', 1, ?) RETURNING id
                """, Long.class, claimId, staffId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO claim_document_content (document_id, content) VALUES (?, ?)",
                documentId, new byte[0]))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_claim_document_content_size");
    }

    /** 상품·버전 1건을 없을 때만 만들고 버전 id를 돌려준다 (계약 FK용 최소 데이터). */
    private long ensureProductVersion() {
        jdbc.update("""
                INSERT INTO product (product_code, short_name, terms_code)
                VALUES ('T000001', '(테스트)청구스키마', 'PT00001')
                ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO product_version (product_code, effective_from)
                VALUES ('T000001', DATE '2026-01-01')
                ON CONFLICT DO NOTHING
                """);
        return jdbc.queryForObject(
                "SELECT id FROM product_version WHERE product_code = 'T000001'", Long.class);
    }

    /** 이메일이 겹치지 않는 회원 1명을 만들고 id를 돌려준다. */
    private long insertUser(String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', '테스트', DATE '1996-03-01', 'M', ?) RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local", role);
    }

    /** 이 테스트 계약에 직원 대리 접수 사망 청구 1건을 넣고 id를 돌려준다. */
    private long insertDeathClaim() {
        return jdbc.queryForObject("""
                INSERT INTO claim (contract_id, type, accident_date, claimant_name, claimant_relation,
                                   intake_channel, created_by, received_on)
                VALUES (?, 'DEATH', DATE '2027-06-20', '이수익', 'SPOUSE', 'STAFF', ?, DATE '2027-06-22')
                RETURNING id
                """, Long.class, contractId, staffId);
    }

    /** 이 테스트 계약에 고객 본인 장해 청구 1건을 넣고 id를 돌려준다. */
    private long insertDisabilityClaim() {
        return jdbc.queryForObject("""
                INSERT INTO claim (contract_id, type, accident_date, accident_description, claimant_name,
                                   claimant_relation, intake_channel, created_by, received_on)
                VALUES (?, 'DISABILITY', DATE '2027-03-02', '작업 중 사고', '김보험', 'SELF', 'CUSTOMER', ?,
                        DATE '2027-03-10')
                RETURNING id
                """, Long.class, contractId, staffId);
    }

    /** 청구에 PENDING 지급건 1개를 만들고 id를 돌려준다. */
    private long insertPayout(long claimId) {
        return jdbc.queryForObject("""
                INSERT INTO payout (claim_id, amount, approved_on) VALUES (?, 50000000, DATE '2027-06-25')
                RETURNING id
                """, Long.class, claimId);
    }

    /** 청구에 판정 기록 1줄을 추가하고 id를 돌려준다. */
    private long insertAssessment(long claimId, int rate) {
        return jdbc.queryForObject("""
                INSERT INTO claim_assessment (claim_id, disability_rate, basis, reason, assessed_by, assessed_on)
                VALUES (?, ?, '별표3 8.팔 1) 한 팔의 손목 이상을 잃었을 때 (60)', '진단서 확인', ?, DATE '2027-03-12')
                RETURNING id
                """, Long.class, claimId, rate, staffId);
    }
}
