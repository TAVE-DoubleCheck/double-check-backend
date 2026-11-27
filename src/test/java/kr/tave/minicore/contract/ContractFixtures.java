package kr.tave.minicore.contract;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 계약 테스트용 데이터를 DB에 직접 넣는 도우미.
 * 월 보험료 165,350원, 가입금액 5천만 원, 종신('99') 계약을 기본으로 만든다.
 */
public class ContractFixtures {

    public static final long PREMIUM = 165_350L;

    private final JdbcTemplate jdbc;
    private final long versionId;

    public ContractFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.update("""
                INSERT INTO product (product_code, short_name, terms_code)
                VALUES ('T000009', '(테스트)납부', 'PT00009') ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO product_version (product_code, effective_from)
                VALUES ('T000009', DATE '2026-01-01') ON CONFLICT DO NOTHING
                """);
        this.versionId = jdbc.queryForObject(
                "SELECT id FROM product_version WHERE product_code = 'T000009'", Long.class);
    }

    public long customer() {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', '테스트', DATE '1996-03-01', 'M', 'CUSTOMER') RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local");
    }

    /** 청약(PENDING) + 이력 + 1회차 청구서(납입기일 = 신청일, 마감 = 신청일 + 30일). 반환: 계약 id */
    public long pending(long userId, LocalDate appliedOn) {
        long id = insertContract(userId, appliedOn, null, "PENDING", 10, false);
        history(id, null, "PENDING", appliedOn);
        bill(id, 1, appliedOn, appliedOn.plusDays(30));
        return id;
    }

    /** 유지(ACTIVE) 계약. 1회차는 계약일에 낸 것으로 만든다. 반환: 계약 id */
    public long active(long userId, LocalDate contractDate, int payTermYears, boolean autoDebit) {
        long id = insertContract(userId, contractDate.minusDays(5), contractDate, "ACTIVE", payTermYears, autoDebit);
        history(id, null, "PENDING", contractDate.minusDays(5));
        history(id, "PENDING", "ACTIVE", contractDate);
        paidBill(id, 1, contractDate);
        return id;
    }

    /** 발행 상태 청구서. 반환: 청구서 id */
    public long bill(long contractId, int installmentNo, LocalDate due, LocalDate graceEnd) {
        return jdbc.queryForObject("""
                INSERT INTO premium_bill (contract_id, installment_no, due_date, grace_end_date, amount)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, contractId, installmentNo, due, graceEnd, PREMIUM);
    }

    /** 이미 낸 청구서 (장부 없이). 반환: 청구서 id */
    public long paidBill(long contractId, int installmentNo, LocalDate due) {
        return jdbc.queryForObject("""
                INSERT INTO premium_bill (contract_id, installment_no, due_date, grace_end_date, amount,
                                          status, paid_on, paid_channel)
                VALUES (?, ?, ?, ?, ?, 'PAID', ?, 'ONLINE') RETURNING id
                """, Long.class, contractId, installmentNo, due, due.plusMonths(2), PREMIUM, due);
    }

    public String status(long contractId) {
        return jdbc.queryForObject("SELECT status FROM contract WHERE id = ?", String.class, contractId);
    }

    public String billStatus(long billId) {
        return jdbc.queryForObject("SELECT status FROM premium_bill WHERE id = ?", String.class, billId);
    }

    public int ledgerCount(long contractId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger WHERE contract_id = ?", Integer.class, contractId);
    }

    private long insertContract(long userId, LocalDate appliedOn, LocalDate contractDate, String status,
                                int payTermYears, boolean autoDebit) {
        return jdbc.queryForObject("""
                INSERT INTO contract (user_id, version_id, sex, insurance_age, insured_amount, monthly_premium,
                                      pay_term_plan_type, pay_term_plan_value, pay_term_years, insure_term_code,
                                      applied_on, contract_date, billing_day, auto_debit, status,
                                      beneficiary_name, beneficiary_relation)
                VALUES (?, ?, 'M', 30, 50000000, ?, 'FIXED_YEARS', ?, ?, '99', ?, ?, ?, ?, ?, '이수익', 'SPOUSE')
                RETURNING id
                """, Long.class, userId, versionId, PREMIUM, payTermYears, payTermYears, appliedOn, contractDate,
                contractDate == null ? null : (short) contractDate.getDayOfMonth(), autoDebit, status);
    }

    private void history(long contractId, String from, String to, LocalDate on) {
        jdbc.update("""
                INSERT INTO contract_history (contract_id, from_status, to_status, reason, actor_type, occurred_on)
                VALUES (?, ?, ?, '테스트 준비', 'BATCH', ?)
                """, contractId, from, to, on);
    }
}
