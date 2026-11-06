package kr.tave.minicore.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상식 검증(checks/*.sql)을 실제 적재본 덤프(seed/product_seed.sql) 위에서 실행한다.
 * 각 SQL은 "위반 행"을 반환하며, 비교 쌍이 0개면 NO_PAIRS 행을 반환하므로
 * 결과 0행이면 (1) 위반 없음 + (2) 비교 쌍 존재가 동시에 보장된다.
 * seed를 재생성하면(예: 나이 범위 확장 재적재) 이 테스트가 새 데이터로 다시 검증한다.
 */
@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@Testcontainers
class SanityCheckIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    private static boolean seeded = false;

    @BeforeEach
    void loadSeed() throws Exception {
        if (seeded) {
            return;
        }
        postgres.copyFileToContainer(
                MountableFile.forHostPath(Path.of("seed/product_seed.sql")), "/tmp/product_seed.sql");
        var result = postgres.execInContainer(
                "psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                "-v", "ON_ERROR_STOP=1", "-f", "/tmp/product_seed.sql");
        assertThat(result.getExitCode())
                .as("seed 복원 실패: %s", result.getStderr())
                .isZero();
        seeded = true;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "01_refund50_lt_standard.sql",
            "02_simplified_gt_general.sql",
            "03_longer_pay_term_cheaper.sql"
    })
    void 상식_검증_SQL은_위반_0행이어야_한다(String file) throws Exception {
        String sql = Files.readString(Path.of("checks", file));

        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        assertThat(rows)
                .as("%s 위반 행 (NO_PAIRS면 조인 조건 오류): %s", file, rows)
                .isEmpty();
    }

    /** seed가 비어 있으면 위 검증이 NO_PAIRS로만 실패하므로, 원인 구분용으로 행 수도 못박는다. */
    @ParameterizedTest
    @ValueSource(strings = {"product", "product_version", "premium_rate", "coverage"})
    void seed_복원_후_테이블이_비어_있지_않다(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        assertThat(count).as("%s 행 수", table).isPositive();
    }
}
