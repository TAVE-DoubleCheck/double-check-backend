package kr.tave.minicore.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "ingest.run-on-startup=false")
@Testcontainers
class LedgerSchemaIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private static final LocalDate PAID_ON = LocalDate.of(2026, 11, 2);
    private long staffId;
    private long contractId;
    private long otherContractId;
    private record Source(boolean outgoing, long id, long amount) { }

    @BeforeEach
    void prepareSourceRecords() {
        String code = "L" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO product(product_code, short_name, terms_code) VALUES (?, ?, ?)", code, code, code);
        long version = jdbc.queryForObject("""
                INSERT INTO product_version(product_code, effective_from)
                VALUES (?, DATE '2026-01-01') RETURNING id
                """, Long.class, code);
        staffId = jdbc.queryForObject("""
                INSERT INTO users(email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', 'ledger-test', DATE '1996-01-01', 'M', 'ADMIN') RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local");
        contractId = insertContract(version);
        otherContractId = insertContract(version);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validSourceProducesAnEntryWithTechnicalTimestamp(boolean outgoing) {
        Source source = source(outgoing, true);
        long id = insert(source, contractId, source.amount(), PAID_ON);
        var row = jdbc.queryForMap("SELECT * FROM ledger WHERE id = ?", id);
        assertThat(row.get("amount")).isEqualTo(source.amount());
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get(outgoing ? "premium_bill_id" : "payout_id")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"false,contract", "false,amount", "false,date", "true,contract", "true,amount", "true,date"})
    void originalContractAmountAndDateAreCheckedByDatabase(boolean outgoing, String field) {
        Source s = source(outgoing, true);
        assertThatThrownBy(() -> insert(s, field.equals("contract") ? otherContractId : contractId,
                field.equals("amount") ? s.amount() + 1 : s.amount(),
                field.equals("date") ? PAID_ON.plusDays(1) : PAID_ON))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ledger source mismatch");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompleteSourceCannotBeRecorded(boolean outgoing) {
        Source s = source(outgoing, false);
        assertThatThrownBy(() -> insert(s, contractId, s.amount(), PAID_ON))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ledger source mismatch");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOriginalIsRejected(boolean outgoing) {
        assertThatThrownBy(() -> insert(new Source(outgoing, -1, 100), contractId, 100, PAID_ON))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("source does not exist");
    }

    @ParameterizedTest
    @CsvSource({"PREMIUM_IN,both", "CLAIM_OUT,both", "PREMIUM_IN,none", "CLAIM_OUT,none",
            "PREMIUM_IN,payout", "CLAIM_OUT,bill", "OTHER,bill"})
    void invalidTypeAndReferencePairsAreRejected(String type, String pair) {
        Source bill = source(false, true);
        Source payout = source(true, true);
        Long billId = pair.equals("both") || pair.equals("bill") ? bill.id() : null;
        Long payoutId = pair.equals("both") || pair.equals("payout") ? payout.id() : null;
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger(contract_id, type, amount, occurred_on, premium_bill_id, payout_id)
                VALUES (?, ?, 100, ?, ?, ?)
                """, contractId, type, PAID_ON, billId, payoutId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_ledger_source_pair");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveAmountsAreRejected(long amount) {
        Source s = source(false, true);
        assertThatThrownBy(() -> insert(s, contractId, amount, PAID_ON)).isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sourceCannotBeRecordedTwice(boolean outgoing) {
        Source s = source(outgoing, true);
        insert(s, contractId, s.amount(), PAID_ON);
        assertThatThrownBy(() -> insert(s, contractId, s.amount(), PAID_ON))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining(outgoing ? "uq_ledger_payout" : "uq_ledger_premium_bill");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE ledger SET amount = amount + 1 WHERE id = ?", "DELETE FROM ledger WHERE id = ?"})
    void ledgerIsAppendOnly(String sql) {
        Source s = source(false, true);
        long id = insert(s, contractId, s.amount(), PAID_ON);
        assertThatThrownBy(() -> jdbc.update(sql, id)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ledger is append-only");
        assertThat(jdbc.queryForObject("SELECT amount FROM ledger WHERE id = ?", Long.class, id)).isEqualTo(s.amount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingOriginalCannotDeleteLedger(boolean outgoing) {
        Source s = source(outgoing, true);
        long id = insert(s, contractId, s.amount(), PAID_ON);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + (outgoing ? "payout" : "premium_bill") + " WHERE id = ?", s.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("foreign key");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedLedgerInsertRollsBackThePrecedingPaymentUpdate(boolean outgoing) {
        Source s = source(outgoing, false);
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            markPaid(s);
            insert(s, contractId, s.amount() + 1, PAID_ON);
        })).isInstanceOf(DataAccessException.class);
        String table = outgoing ? "payout" : "premium_bill";
        var row = jdbc.queryForMap("SELECT status, paid_on FROM " + table + " WHERE id = ?", s.id());
        assertThat(row.get("status")).isEqualTo(outgoing ? "PENDING" : "ISSUED");
        assertThat(row.get("paid_on")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger WHERE contract_id = ?", Integer.class, contractId)).isZero();
    }

    @Test
    void completionAndLedgerCommitTogether() {
        Source s = source(false, false);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            markPaid(s);
            insert(s, contractId, s.amount(), PAID_ON);
        });
        assertThat(jdbc.queryForObject("SELECT status FROM premium_bill WHERE id = ?", String.class, s.id())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger WHERE premium_bill_id = ?", Integer.class, s.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"false,amount", "false,date", "false,status", "false,contract",
            "true,amount", "true,date", "true,status", "true,contract"})
    void recordedOriginalCannotBeChangedToInvalidateLedger(boolean outgoing, String field) {
        Source s = source(outgoing, true);
        insert(s, contractId, s.amount(), PAID_ON);
        String table = outgoing ? "payout" : "premium_bill";
        String sql = switch (field) {
            case "amount" -> "UPDATE " + table + " SET amount = amount + 1 WHERE id = ?";
            case "date" -> "UPDATE " + table + " SET paid_on = paid_on + 1 WHERE id = ?";
            case "status" -> "UPDATE " + table + " SET status = 'PENDING', paid_on = NULL WHERE id = ?";
            default -> outgoing
                    ? "UPDATE claim SET contract_id = " + otherContractId + " WHERE id = (SELECT claim_id FROM payout WHERE id = ?)"
                    : "UPDATE premium_bill SET contract_id = " + otherContractId + " WHERE id = ?";
        };
        assertThatThrownBy(() -> jdbc.update(sql, s.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("recorded ledger source cannot change");
        // Non-financial metadata remains editable for other features.
        jdbc.update("UPDATE " + table + " SET updated_at = now() WHERE id = ?", s.id());
    }

    @Test
    void concurrentDuplicateRequestsStoreOnlyOneRow() throws Exception {
        Source s = source(false, true);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Boolean> attempt = () -> {
            try { insert(s, contractId, s.amount(), PAID_ON); return true; }
            catch (DuplicateKeyException expected) { return false; }
        };
        try {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger WHERE premium_bill_id = ?", Integer.class, s.id())).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void testOwnerCanTruncateLedger() {
        Source s = source(false, true);
        insert(s, contractId, s.amount(), PAID_ON);
        jdbc.execute("TRUNCATE ledger RESTART IDENTITY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger", Integer.class)).isZero();
    }

    private long insertContract(long version) {
        return jdbc.queryForObject("""
                INSERT INTO contract(user_id, version_id, sex, insurance_age, insured_amount, monthly_premium,
                    pay_term_plan_type, pay_term_plan_value, pay_term_years, insure_term_code,
                    applied_on, contract_date, billing_day, status, beneficiary_name, beneficiary_relation)
                VALUES (?, ?, 'M', 30, 50000000, 100, 'FIXED_YEARS', 10, 10, '99',
                    DATE '2026-11-01', DATE '2026-11-01', 1, 'ACTIVE', 'beneficiary', 'SPOUSE') RETURNING id
                """, Long.class, staffId, version);
    }

    private Source source(boolean outgoing, boolean paid) {
        if (!outgoing) {
            long id = jdbc.queryForObject("""
                    INSERT INTO premium_bill(contract_id, installment_no, due_date, grace_end_date, amount,
                        status, paid_on, paid_channel)
                    VALUES (?, 1, DATE '2026-11-01', DATE '2027-01-31', 100, ?, ?, ?) RETURNING id
                    """, Long.class, contractId, paid ? "PAID" : "ISSUED", paid ? PAID_ON : null, paid ? "ONLINE" : null);
            return new Source(false, id, 100);
        }
        long claim = jdbc.queryForObject("""
                INSERT INTO claim(contract_id, type, status, accident_date, claimant_name, claimant_relation,
                    intake_channel, created_by, received_on, decided_on)
                VALUES (?, 'DEATH', 'APPROVED', DATE '2026-11-01', 'beneficiary', 'SPOUSE',
                    'STAFF', ?, DATE '2026-11-02', DATE '2026-11-02') RETURNING id
                """, Long.class, contractId, staffId);
        long id = jdbc.queryForObject("""
                INSERT INTO payout(claim_id, amount, status, approved_on, paid_on, paid_by)
                VALUES (?, 50000000, ?, DATE '2026-11-02', ?, ?) RETURNING id
                """, Long.class, claim, paid ? "PAID" : "PENDING", paid ? PAID_ON : null, paid ? staffId : null);
        return new Source(true, id, 50000000);
    }

    private void markPaid(Source s) {
        if (s.outgoing()) jdbc.update("UPDATE payout SET status = 'PAID', paid_on = ?, paid_by = ? WHERE id = ?", PAID_ON, staffId, s.id());
        else jdbc.update("UPDATE premium_bill SET status = 'PAID', paid_on = ?, paid_channel = 'ONLINE' WHERE id = ?", PAID_ON, s.id());
    }

    private long insert(Source s, long contract, long amount, LocalDate date) {
        return jdbc.queryForObject("""
                INSERT INTO ledger(contract_id, type, amount, occurred_on, premium_bill_id, payout_id)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, contract, s.outgoing() ? "CLAIM_OUT" : "PREMIUM_IN", amount, date,
                s.outgoing() ? null : s.id(), s.outgoing() ? s.id() : null);
    }
}
