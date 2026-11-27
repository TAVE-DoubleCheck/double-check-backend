package kr.tave.minicore.daily;

import kr.tave.minicore.clock.ClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

// 계약 단계(BILLING·AUTO_DEBIT·LAPSE·MATURITY) 실제 빈을 끄고 아래 테스트 빈 5개로 뼈대만 확인한다 (BE B 연결 후 추가)
@SpringBootTest(properties = {"ingest.run-on-startup=false", "daily.contract-steps.enabled=false"})
@Import(DailyWorkIntegrationTest.Steps.class)
@Testcontainers
class DailyWorkIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired DailyWorkService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClockService clock;
    @Autowired Probe probe;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void prepare() {
        // 처리 순서와 롤백을 확인할 테스트 전용 기록이다.
        jdbc.execute("CREATE TABLE IF NOT EXISTS daily_work_probe (id BIGSERIAL, business_date DATE, stage VARCHAR(30))");
        jdbc.execute("TRUNCATE daily_work_probe");
        probe.failureDate = null;
        probe.entered = null;
        probe.release = null;
    }

    @Test
    void advancesOneDayAtATimeAndUsesUpdatedBusinessDateInFixedOrder() {
        LocalDate initial = clock.today();
        assertThat(service.advanceTo(initial.plusDays(3))).isEqualTo(initial.plusDays(3));
        assertThat(clock.today()).isEqualTo(initial.plusDays(3));
        List<String> expected = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            for (DailyWorkStage stage : DailyWorkStage.values()) {
                expected.add(initial.plusDays(day) + ":" + stage);
            }
        }
        assertThat(records()).containsExactlyElementsOf(expected);
    }

    @Test
    void rollsBackOnlyFailedDayAndResumesFromIt() {
        LocalDate initial = clock.today();
        probe.failureDate = initial.plusDays(2);
        assertThatThrownBy(() -> service.advanceTo(initial.plusDays(3)))
                .isInstanceOfSatisfying(DailyWorkException.class,
                        failure -> assertThat(failure.failedDate()).isEqualTo(initial.plusDays(2)));
        assertThat(clock.today()).isEqualTo(initial.plusDays(1));
        assertThat(records()).hasSize(5).allMatch(record -> record.startsWith(initial.plusDays(1).toString()));
        probe.failureDate = null;
        service.advanceTo(initial.plusDays(3));
        assertThat(records()).hasSize(15);
        assertThat(clock.today()).isEqualTo(initial.plusDays(3));
    }

    @Test
    void sameDateDoesNothingAndPastOrNullIsRejected() {
        LocalDate today = clock.today();
        assertThat(service.advanceTo(today)).isEqualTo(today);
        assertThatThrownBy(() -> service.advanceTo(today.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.advanceTo(null)).isInstanceOf(NullPointerException.class);
        assertThat(records()).isEmpty();
        assertThat(clock.today()).isEqualTo(today);
    }

    @Test
    void missingStepStopsBeforeChangingDate() {
        LocalDate today = clock.today();
        DailyWorkService incomplete = new DailyWorkService(jdbc, transactionManager, List.of());
        assertThatThrownBy(() -> incomplete.advanceTo(today.plusDays(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BILLING");
        assertThat(clock.today()).isEqualTo(today);
        assertThat(records()).isEmpty();
    }

    @Test
    void duplicateStageIsRejectedAtRegistration() {
        DailyWorkStep step = probe.step(DailyWorkStage.BILLING);
        assertThatThrownBy(() -> new DailyWorkService(jdbc, transactionManager, List.of(step, step)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void refusesAnOuterTransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> service.advanceTo(clock.today().plusDays(1))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(records()).isEmpty();
    }

    @Test
    void concurrentRequestsDoNotProcessSameDateTwice() throws Exception {
        LocalDate target = clock.today().plusDays(1);
        probe.entered = new CountDownLatch(1);
        probe.release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.advanceTo(target));
            assertThat(probe.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                secondStarted.countDown();
                return service.advanceTo(target);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // 두 번째 연결이 실제 DB 잠금을 기다리는 것을 확인한 뒤 첫 요청을 완료한다.
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                          AND query LIKE '%SELECT business_date FROM system_clock%FOR UPDATE%'
                        """, Integer.class) == 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("Lock wait was not observed");
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
            });
            probe.release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(target);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(target);
            assertThat(records()).hasSize(5).doesNotHaveDuplicates();
            assertThat(clock.today()).isEqualTo(target);
        } finally {
            probe.release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private List<String> records() {
        return jdbc.query("SELECT business_date, stage FROM daily_work_probe ORDER BY id",
                (rs, index) -> rs.getDate(1).toLocalDate() + ":" + rs.getString(2));
    }

    static class Probe {
        private final JdbcTemplate jdbc;
        private final ClockService clock;
        volatile LocalDate failureDate;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        Probe(JdbcTemplate jdbc, ClockService clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        DailyWorkStep step(DailyWorkStage stage) {
            return new DailyWorkStep() {
                @Override public DailyWorkStage stage() { return stage; }

                @Override public void execute(LocalDate date) {
                    assertThat(clock.today()).isEqualTo(date);
                    jdbc.update("INSERT INTO daily_work_probe (business_date, stage) VALUES (?, ?)", date, stage.name());
                    if (stage == DailyWorkStage.BILLING && entered != null) {
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting for second request");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    if (stage == DailyWorkStage.AUTO_DEBIT && date.equals(failureDate)) {
                        throw new IllegalStateException("Simulated auto debit failure");
                    }
                }
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Steps {
        @Bean Probe probe(JdbcTemplate jdbc, ClockService clock) { return new Probe(jdbc, clock); }
        // 실행 순서와 반대로 등록해 빈 등록 순서에 의존하지 않음을 검증한다.
        @Bean DailyWorkStep reconciliation(Probe p) { return p.step(DailyWorkStage.RECONCILIATION); }
        @Bean DailyWorkStep maturity(Probe p) { return p.step(DailyWorkStage.MATURITY); }
        @Bean DailyWorkStep lapse(Probe p) { return p.step(DailyWorkStage.LAPSE); }
        @Bean DailyWorkStep autoDebit(Probe p) { return p.step(DailyWorkStage.AUTO_DEBIT); }
        @Bean DailyWorkStep billing(Probe p) { return p.step(DailyWorkStage.BILLING); }
    }
}
