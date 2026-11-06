package kr.tave.minicore.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 실제 PostgreSQL(Testcontainers) + Flyway 마이그레이션 + 가짜 API 응답(픽스처)으로 적재 전체를 실행한다.
 * Docker Desktop이 켜져 있어야 한다.
 */
@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key",
        "ingest.call-interval-ms=0",
        "ingest.premium.age-min=30",
        "ingest.premium.age-max=30"
})
@Testcontainers
class IngestServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    PublicApiClient client;

    @Autowired
    IngestService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void stubApi() {
        jdbc.execute("TRUNCATE coverage, premium_rate, product_version, product, raw_api_response, ingest_log RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE system_clock SET business_date = DATE '2026-10-27' WHERE business_date < DATE '2026-10-27'");

        when(client.get(anyString(), anyString(), anyMap())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            Map<String, String> params = inv.getArgument(2);
            if (url.contains("TermsView")) {
                return Fixtures.read("terms_hanaro_real.json");
            }
            if (url.contains("FeeCalculation")) {
                return "M".equals(params.get("SEX_S1"))
                        ? Fixtures.read("premium_hanaro_30M_SYNTHETIC.json")
                        : Fixtures.EMPTY_RESPONSE;
            }
            if (url.contains("GuaranteeContent")) {
                return Fixtures.read("coverage_page1_real.json");   // 20줄 < page-size 100 → 1페이지로 종료
            }
            throw new IllegalArgumentException(url);
        });
    }

    @Test
    void 두_번_적재해도_행_수가_같다_멱등() {
        service.run();
        long products = count("product"), versions = count("product_version"),
                rates = count("premium_rate"), coverages = count("coverage");

        service.run();

        assertThat(count("product")).isEqualTo(products).isEqualTo(8);
        assertThat(count("product_version")).isEqualTo(versions).isEqualTo(8);
        assertThat(count("premium_rate")).isEqualTo(rates).isEqualTo(16);
        assertThat(count("coverage")).isEqualTo(coverages).isEqualTo(7);
    }

    @Test
    void DB에서_월보험료와_납입면제_문단을_조회할_수_있다() {
        service.run();

        Long monthly = jdbc.queryForObject("""
                SELECT r.premium_won * 50000000 / r.unit_amount_won
                  FROM premium_rate r JOIN product_version v ON v.id = r.version_id
                 WHERE v.product_code = '4008400' AND r.join_age = 30 AND r.sex = 'M'
                   AND r.pay_term_years = 10 AND r.insure_term_code = '99'""", Long.class);
        assertThat(monthly).isEqualTo(165_350L);

        Integer notes = jdbc.queryForObject("""
                SELECT count(*) FROM coverage c JOIN product_version v ON v.id = c.version_id
                 WHERE v.product_code = '4008400' AND c.is_note AND c.condition_text LIKE '%납입을 면제합니다.'""",
                Integer.class);
        assertThat(notes).isEqualTo(1);
    }

    @Test
    void 성공하면_API별_로그와_원문이_남는다() {
        service.run();

        assertThat(jdbc.queryForList("SELECT status FROM ingest_log", String.class))
                .containsExactlyInAnyOrder("SUCCESS", "SUCCESS", "SUCCESS");
        // 약관 1 + 보험료 2(30세 M/W) + 보장 1
        assertThat(count("raw_api_response")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT call_count FROM ingest_log WHERE api = 'PREMIUM'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void 검증에_실패하면_데이터는_그대로이고_로그는_FAILED_원문은_보관() {
        when(client.get(anyString(), anyString(), anyMap())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (url.contains("TermsView")) {
                return Fixtures.read("terms_hanaro_real.json");
            }
            if (url.contains("FeeCalculation")) {
                return Fixtures.EMPTY_RESPONSE;   // 약관 상품의 보험료가 하나도 없음 → 검증 실패
            }
            return Fixtures.read("coverage_page1_real.json");
        });

        assertThatThrownBy(() -> service.run()).isInstanceOf(IngestPlanner.IngestValidationException.class);

        assertThat(count("product")).isZero();
        assertThat(count("premium_rate")).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM ingest_log", String.class)).containsExactly("FAILED");
        assertThat(count("raw_api_response")).isEqualTo(4);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
