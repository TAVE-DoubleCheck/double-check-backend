package kr.tave.minicore.contract.batch;

// 계약 매일 작업 단계(BILLING·AUTO_DEBIT·LAPSE·MATURITY) 통합 테스트.
// DailyWorkService처럼 하루를 한 트랜잭션으로 묶어 단계를 직접 실행한다.
// (장부 대사 RECONCILIATION 단계가 아직 없어 advanceTo 전체는 여기서 돌리지 않는다)

import kr.tave.minicore.contract.ContractFixtures;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.domain.PremiumSchedule;
import kr.tave.minicore.contract.service.ContractStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@Testcontainers
class ContractDailyStepsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired BillingStep billing;
    @Autowired AutoDebitStep autoDebit;
    @Autowired LapseStep lapse;
    @Autowired MaturityStep maturity;
    @Autowired ContractStatusService statusService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private ContractFixtures fx;
    private long me;

    @BeforeEach
    void setUp() {
        // 장부는 지울 수 없게(append-only) 막혀 있어 비우지 않는다. 앞 테스트의 계약도 단계에서 같이 처리되지만
        // 각 테스트는 자기가 만든 계약만 확인한다
        fx = new ContractFixtures(jdbc);
        me = fx.customer();
    }

    @Test
    void 청구서는_납입기일에_한_장씩_말일_계약도_맞게_발행된다() {
        long c = fx.active(me, LocalDate.of(2026, 1, 31), 10, false);

        day(LocalDate.of(2026, 2, 27), billing::execute);
        assertThat(bills(c)).hasSize(1);

        day(LocalDate.of(2026, 2, 28), billing::execute);
        day(LocalDate.of(2026, 2, 28), billing::execute); // 같은 날 다시 돌려도 중복 없음
        assertThat(bills(c)).hasSize(2);
        assertThat(bill(c, 2)).containsEntry("status", "ISSUED").containsEntry("amount", ContractFixtures.PREMIUM);
        assertThat(bill(c, 2).get("due_date").toString()).isEqualTo("2026-02-28");
        assertThat(bill(c, 2).get("grace_end_date").toString()).isEqualTo("2026-04-30");

        day(LocalDate.of(2026, 3, 31), billing::execute);
        assertThat(bill(c, 3).get("due_date").toString()).isEqualTo("2026-03-31");
        assertThat(bill(c, 3).get("grace_end_date").toString()).isEqualTo("2026-05-31");
    }

    @Test
    void 자동이체가_켜진_계약만_납입기일에_자동_납부되고_장부에_남는다() {
        long on = fx.active(me, LocalDate.of(2026, 1, 15), 10, true);
        long off = fx.active(me, LocalDate.of(2026, 1, 15), 10, false);
        LocalDate due = LocalDate.of(2026, 2, 15);

        day(due, d -> {
            billing.execute(d);
            autoDebit.execute(d);
        });

        assertThat(bill(on, 2)).containsEntry("status", "PAID").containsEntry("paid_channel", "AUTO_DEBIT");
        assertThat(bill(on, 2).get("paid_on").toString()).isEqualTo("2026-02-15");
        assertThat(fx.ledgerCount(on)).isEqualTo(1);
        assertThat(bill(off, 2)).containsEntry("status", "ISSUED");
        assertThat(fx.ledgerCount(off)).isZero();
    }

    @Test
    void 유예_마감까지_안_내면_다음_날_실효된다() {
        long c = fx.active(me, LocalDate.of(2026, 2, 15), 10, false);
        long second = fx.bill(c, 2, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 5, 31));

        day(LocalDate.of(2026, 3, 16), lapse::execute);
        assertThat(fx.billStatus(second)).as("납입기일 다음 날 미납 표시").isEqualTo("OVERDUE");
        assertThat(fx.status(c)).isEqualTo("ACTIVE");

        day(LocalDate.of(2026, 5, 31), lapse::execute);
        assertThat(fx.status(c)).as("유예 마감일 당일은 아직 유지").isEqualTo("ACTIVE");

        day(LocalDate.of(2026, 6, 1), lapse::execute);
        assertThat(fx.status(c)).isEqualTo("LAPSED");
        Map<String, Object> h = jdbc.queryForMap("""
                SELECT actor_type, actor_id, occurred_on, reason FROM contract_history
                WHERE contract_id = ? AND to_status = 'LAPSED'""", c);
        assertThat(h).containsEntry("actor_type", "BATCH");
        assertThat(h.get("actor_id")).isNull();
        assertThat(h.get("occurred_on").toString()).isEqualTo("2026-06-01");
        assertThat((String) h.get("reason")).contains("#" + second);

        day(LocalDate.of(2026, 6, 2), lapse::execute); // 이미 실효면 다시 처리하지 않는다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contract_history WHERE contract_id = ?",
                Integer.class, c)).isEqualTo(3);
    }

    @Test
    void 일회차를_대기_마감까지_안_내면_불성립() {
        long c = fx.pending(me, LocalDate.of(2026, 3, 10)); // 마감 4/9

        day(LocalDate.of(2026, 3, 11), lapse::execute);
        assertThat(fx.billStatus(firstBill(c))).as("1회차는 미납으로 표시하지 않음").isEqualTo("ISSUED");

        day(LocalDate.of(2026, 4, 9), lapse::execute);
        assertThat(fx.status(c)).isEqualTo("PENDING");

        day(LocalDate.of(2026, 4, 10), lapse::execute);
        assertThat(fx.status(c)).isEqualTo("VOID");
    }

    @Test
    void 만기_단계는_종신_계약만_있으면_통과한다() {
        fx.active(me, LocalDate.of(2026, 1, 15), 10, false);
        day(LocalDate.of(2026, 2, 1), maturity::execute);
    }

    /**
     * 엔진 시나리오: 2/15 계약, 3/15부터 안 냄. 하루씩 네 단계를 돌리면
     * 6/1(= PremiumSchedule.lapseDate(3/15))에 실효되고, 그 뒤로는 청구서가 더 나오지 않는다.
     * 시뮬레이터 대조 테스트가 기대하는 "예상 실효일 == 실제 실효일"의 기준.
     */
    @Test
    void 하루씩_돌리면_예상_실효일에_실효되고_이후_발행이_멈춘다() {
        long c = fx.active(me, LocalDate.of(2026, 2, 15), 10, false);
        LocalDate expectedLapse = PremiumSchedule.lapseDate(LocalDate.of(2026, 3, 15));

        for (LocalDate d = LocalDate.of(2026, 2, 16); !d.isAfter(LocalDate.of(2026, 7, 31)); d = d.plusDays(1)) {
            day(d, x -> {
                billing.execute(x);
                autoDebit.execute(x);
                lapse.execute(x);
                maturity.execute(x);
            });
        }

        assertThat(expectedLapse).isEqualTo("2026-06-01");
        assertThat(fx.status(c)).isEqualTo("LAPSED");
        assertThat(statusService.statusOn(c, expectedLapse.minusDays(1))).contains(ContractStatus.ACTIVE);
        assertThat(statusService.statusOn(c, expectedLapse)).contains(ContractStatus.LAPSED);
        // 2(3/15)·3(4/15)·4(5/15)회차까지 발행, 실효 후인 6/15·7/15는 발행 안 함
        assertThat(bills(c)).extracting(m -> ((Number) m.get("installment_no")).intValue())
                .containsExactly(1, 2, 3, 4);
    }

    private void day(LocalDate date, java.util.function.Consumer<LocalDate> work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> work.accept(date));
    }

    private List<Map<String, Object>> bills(long contractId) {
        return jdbc.queryForList(
                "SELECT installment_no FROM premium_bill WHERE contract_id = ? ORDER BY installment_no", contractId);
    }

    private Map<String, Object> bill(long contractId, int installmentNo) {
        return jdbc.queryForMap("""
                SELECT status, amount, due_date, grace_end_date, paid_on, paid_channel
                FROM premium_bill WHERE contract_id = ? AND installment_no = ?""", contractId, installmentNo);
    }

    private long firstBill(long contractId) {
        return jdbc.queryForObject(
                "SELECT id FROM premium_bill WHERE contract_id = ? AND installment_no = 1", Long.class, contractId);
    }
}
