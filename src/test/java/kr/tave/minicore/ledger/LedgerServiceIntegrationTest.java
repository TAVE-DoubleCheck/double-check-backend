package kr.tave.minicore.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SpringBootTest(properties = "ingest.run-on-startup=false")
@Testcontainers
class LedgerServiceIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired LedgerService service;

    private static final LocalDate PAID_ON = LocalDate.of(2026, 11, 2);
    private long staffId;
    private long contractId;
    private record Source(boolean outgoing, long id, long amount) { }

    @BeforeEach
    void prepare() {
        String code = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO product(product_code, short_name, terms_code) VALUES (?, ?, ?)", code, code, code);
        long version = jdbc.queryForObject("""
                INSERT INTO product_version(product_code, effective_from)
                VALUES (?, DATE '2026-01-01') RETURNING id
                """, Long.class, code);
        staffId = jdbc.queryForObject("""
                INSERT INTO users(email, password_hash, name, birth_date, sex, role)
                VALUES (?, 'hash', 'ledger-service-test', DATE '1996-01-01', 'M', 'ADMIN') RETURNING id
                """, Long.class, UUID.randomUUID() + "@test.local");
        contractId = jdbc.queryForObject("""
                INSERT INTO contract(user_id, version_id, sex, insurance_age, insured_amount, monthly_premium,
                    pay_term_plan_type, pay_term_plan_value, pay_term_years, insure_term_code,
                    applied_on, contract_date, billing_day, status, beneficiary_name, beneficiary_relation)
                VALUES (?, ?, 'M', 30, 50000000, 50000, 'FIXED_YEARS', 10, 10, '99',
                    DATE '2026-11-01', DATE '2026-11-01', 1, 'ACTIVE', 'beneficiary', 'SPOUSE') RETURNING id
                """, Long.class, staffId, version);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsOriginalContractAmountDateAndSourceId(boolean outgoing) {
        Source source = source(outgoing, true);
        LedgerEntry entry = inTransaction(source);
        assertThat(entry.getId()).isPositive();
        assertThat(entry.getContractId()).isEqualTo(contractId);
        assertThat(entry.getType()).isEqualTo(outgoing ? "CLAIM_OUT" : "PREMIUM_IN");
        assertThat(entry.getAmount()).isEqualTo(source.amount());
        assertThat(entry.getOccurredOn()).isEqualTo(PAID_ON);
        assertThat(entry.getCreatedAt()).isNotNull();
        assertThat(entry.getPremiumBillId()).isEqualTo(outgoing ? null : source.id());
        assertThat(entry.getPayoutId()).isEqualTo(outgoing ? source.id() : null);
        assertThat(count(source)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicatesReturnTheSameEntryAndTransactionRemainsUsable(boolean outgoing) {
        Source source = source(outgoing, true);
        LedgerEntry first = inTransaction(source);
        LedgerEntry repeated = new TransactionTemplate(transactions).execute(status -> {
            LedgerEntry entry = record(source);
            assertThat(record(source).getId()).isEqualTo(first.getId());
            // 중복 예외를 잡는 방식과 달리 트랜잭션이 정상 상태임을 확인한다.
            jdbc.update("UPDATE users SET name = 'still-usable' WHERE id = ?", staffId);
            return entry;
        });
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repeated.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(count(source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, staffId))
                .isEqualTo("still-usable");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiresCallerTransaction(boolean outgoing) {
        Source source = source(outgoing, true);
        assertThatThrownBy(() -> record(source)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count(source)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingSourceIsReported(boolean outgoing) {
        assertThatThrownBy(() -> inTransaction(new Source(outgoing, -1, 0)))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseRejectsUnpaidSourceAndRollsBackCallerChanges(boolean outgoing) {
        Source source = source(outgoing, false);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET name = 'must-rollback' WHERE id = ?", staffId);
            record(source);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("ledger source mismatch");
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, staffId))
                .isEqualTo("ledger-service-test");
        assertThat(count(source)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionAndEntryCommitTogether(boolean outgoing) {
        Source source = source(outgoing, false);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            markPaid(source);
            record(source);
        });
        assertThat(sourceStatus(source)).isEqualTo("PAID");
        assertThat(count(source)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerFailureRollsBackCompletionAndEntryTogether(boolean outgoing) {
        Source source = source(outgoing, false);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            markPaid(source);
            record(source);
            throw new IllegalStateException("later caller failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("later caller failure");
        assertThat(sourceStatus(source)).isEqualTo(outgoing ? "PENDING" : "ISSUED");
        assertThat(count(source)).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            markPaid(source);
            record(source);
        });
        assertThat(count(source)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDuplicateWaitsForCommitAndReturnsTheSameEntry(boolean outgoing) throws Exception {
        Source source = source(outgoing, true);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                LedgerEntry entry = record(source);
                inserted.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return entry;
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> inTransaction(source));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                          AND query LIKE '%INSERT INTO ledger%'
                        """, Integer.class) == 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("No lock wait");
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
            });
            release.countDown();
            LedgerEntry firstResult = first.get(20, TimeUnit.SECONDS);
            LedgerEntry secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(secondResult.getId()).isEqualTo(firstResult.getId());
            assertThat(secondResult.getAmount()).isEqualTo(source.amount());
            assertThat(count(source)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private LedgerEntry record(Source source) {
        return source.outgoing() ? service.recordClaimPayout(source.id()) : service.recordPremiumIncome(source.id());
    }

    private LedgerEntry inTransaction(Source source) {
        return new TransactionTemplate(transactions).execute(status -> record(source));
    }

    private int count(Source source) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger WHERE "
                + (source.outgoing() ? "payout_id" : "premium_bill_id") + " = ?", Integer.class, source.id());
    }

    private String sourceStatus(Source source) {
        return jdbc.queryForObject("SELECT status FROM " + (source.outgoing() ? "payout" : "premium_bill")
                + " WHERE id = ?", String.class, source.id());
    }

    private void markPaid(Source source) {
        if (source.outgoing()) {
            jdbc.update("UPDATE payout SET status = 'PAID', paid_on = ?, paid_by = ? WHERE id = ?",
                    PAID_ON, staffId, source.id());
        } else {
            jdbc.update("UPDATE premium_bill SET status = 'PAID', paid_on = ?, paid_channel = 'ONLINE' WHERE id = ?",
                    PAID_ON, source.id());
        }
    }

    private Source source(boolean outgoing, boolean paid) {
        if (!outgoing) {
            long id = jdbc.queryForObject("""
                    INSERT INTO premium_bill(contract_id, installment_no, due_date, grace_end_date, amount,
                        status, paid_on, paid_channel)
                    VALUES (?, 1, DATE '2026-11-01', DATE '2027-01-31', 50000, ?, ?, ?) RETURNING id
                    """, Long.class, contractId, paid ? "PAID" : "ISSUED", paid ? PAID_ON : null, paid ? "ONLINE" : null);
            return new Source(false, id, 50000);
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
}
