package kr.tave.minicore.contract.api;

// 납부·자동이체 설정 API 통합 테스트.
// 업무 날짜를 테스트에서 바꿔 가며 1회차 납부(계약 성립) → 이중 납부 방지 → 마감·회차 순서 → 마지막 회차(납입완료)를 확인한다.
// 납부할 때마다 장부(ledger)에 입금 1줄이 같은 트랜잭션으로 남는지도 본다.

import kr.tave.minicore.auth.domain.Role;
import kr.tave.minicore.auth.security.JwtProvider;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.contract.ContractFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@AutoConfigureMockMvc
@Testcontainers
class PaymentApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** 테스트가 정하는 업무 날짜 */
    static final AtomicReference<LocalDate> TODAY = new AtomicReference<>(LocalDate.of(2026, 3, 15));

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        ClockService testClockService() {
            return TODAY::get;
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    private ContractFixtures fx;
    private long me;

    @BeforeEach
    void setUp() {
        fx = new ContractFixtures(jdbc);
        me = fx.customer();
    }

    @Test
    void 일회차를_내면_계약이_성립하고_장부에_입금이_남는다() throws Exception {
        long contract = fx.pending(me, LocalDate.of(2026, 3, 10));
        long bill = firstBill(contract);
        TODAY.set(LocalDate.of(2026, 3, 15));

        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + bill + "/pay")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.status").value("PAID"))
                .andExpect(jsonPath("$.bill.paidOn").value("2026-03-15"))
                .andExpect(jsonPath("$.bill.paidChannel").value("ONLINE"))
                .andExpect(jsonPath("$.contractStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.ledgerEntryId").isNumber());

        Map<String, Object> c = jdbc.queryForMap(
                "SELECT status, contract_date, billing_day FROM contract WHERE id = ?", contract);
        assertThat(c.get("status")).isEqualTo("ACTIVE");
        assertThat(c.get("contract_date").toString()).isEqualTo("2026-03-15");
        assertThat(((Number) c.get("billing_day")).intValue()).isEqualTo(15);

        Map<String, Object> l = jdbc.queryForMap(
                "SELECT type, amount, occurred_on, premium_bill_id FROM ledger WHERE contract_id = ?", contract);
        assertThat(l).containsEntry("type", "PREMIUM_IN").containsEntry("amount", ContractFixtures.PREMIUM)
                .containsEntry("premium_bill_id", bill);
        assertThat(l.get("occurred_on").toString()).isEqualTo("2026-03-15");

        assertThat(jdbc.queryForObject("""
                SELECT actor_type FROM contract_history WHERE contract_id = ? AND to_status = 'ACTIVE'
                """, String.class, contract)).isEqualTo("CUSTOMER");
    }

    @Test
    void 같은_청구서를_다시_내면_409이고_장부는_1줄() throws Exception {
        long contract = fx.pending(me, LocalDate.of(2026, 3, 10));
        long bill = firstBill(contract);
        TODAY.set(LocalDate.of(2026, 3, 15));
        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + bill + "/pay"))).andExpect(status().isOk());

        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + bill + "/pay")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILL_ALREADY_PAID"));
        assertThat(fx.ledgerCount(contract)).isEqualTo(1);
    }

    @Test
    void 남의_청구서는_404_직원은_403() throws Exception {
        long other = fx.customer();
        long bill = firstBill(fx.pending(other, LocalDate.of(2026, 3, 10)));
        TODAY.set(LocalDate.of(2026, 3, 15));

        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + bill + "/pay")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILL_NOT_FOUND"));
        mvc.perform(as(me, Role.ADMIN, post("/api/bills/" + bill + "/pay")))
                .andExpect(status().isForbidden());
        assertThat(fx.billStatus(bill)).isEqualTo("ISSUED");
    }

    @Test
    void 일회차_대기_마감이_지나면_낼_수_없다() throws Exception {
        long contract = fx.pending(me, LocalDate.of(2026, 3, 10)); // 마감 4/9
        long bill = firstBill(contract);
        TODAY.set(LocalDate.of(2026, 4, 10));

        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + bill + "/pay")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILL_NOT_PAYABLE"));
        assertThat(fx.status(contract)).isEqualTo("PENDING");
        assertThat(fx.ledgerCount(contract)).isZero();
    }

    @Test
    void 앞_회차부터_내야_한다() throws Exception {
        long contract = fx.active(me, LocalDate.of(2026, 1, 15), 10, false);
        long second = fx.bill(contract, 2, LocalDate.of(2026, 2, 15), LocalDate.of(2026, 4, 30));
        long third = fx.bill(contract, 3, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 5, 31));
        TODAY.set(LocalDate.of(2026, 3, 20));

        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + third + "/pay")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILL_NOT_PAYABLE"));

        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + second + "/pay")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("ACTIVE"));
        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + third + "/pay")))
                .andExpect(status().isOk());
        assertThat(fx.ledgerCount(contract)).isEqualTo(2);
    }

    @Test
    void 마지막_회차를_내면_납입완료() throws Exception {
        // 1년납 = 12회. 2025-04-15 계약, 1~11회차 납부 완료, 12회차(2026-03-15) 대기
        LocalDate contractDate = LocalDate.of(2025, 4, 15);
        long contract = fx.active(me, contractDate, 1, false);
        for (int n = 2; n <= 11; n++) {
            fx.paidBill(contract, n, contractDate.plusMonths(n - 1));
        }
        long last = fx.bill(contract, 12, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 5, 31));
        TODAY.set(LocalDate.of(2026, 3, 20));

        mvc.perform(as(me, Role.CUSTOMER, post("/api/bills/" + last + "/pay")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("PAID_UP"));
        assertThat(fx.status(contract)).isEqualTo("PAID_UP");
    }

    @Test
    void 자동이체는_청약_유지_중에만_켜고_끌_수_있다() throws Exception {
        long contract = fx.active(me, LocalDate.of(2026, 1, 15), 10, false);

        mvc.perform(as(me, Role.CUSTOMER, put("/api/contracts/" + contract + "/auto-debit")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoDebit").value(true));
        assertThat(jdbc.queryForObject("SELECT auto_debit FROM contract WHERE id = ?", Boolean.class, contract))
                .isTrue();

        jdbc.update("UPDATE contract SET status = 'LAPSED' WHERE id = ?", contract);
        mvc.perform(as(me, Role.CUSTOMER, put("/api/contracts/" + contract + "/auto-debit")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONTRACT_STATUS"));

        long others = fx.active(fx.customer(), LocalDate.of(2026, 1, 15), 10, false);
        mvc.perform(as(me, Role.CUSTOMER, put("/api/contracts/" + others + "/auto-debit")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")))
                .andExpect(status().isNotFound());
    }

    private long firstBill(long contractId) {
        return jdbc.queryForObject(
                "SELECT id FROM premium_bill WHERE contract_id = ? AND installment_no = 1", Long.class, contractId);
    }

    private MockHttpServletRequestBuilder as(long userId, Role role, MockHttpServletRequestBuilder req) {
        String token = jwtProvider.issue(new LoginUser(userId, "u" + userId + "@test.local", role));
        return req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
