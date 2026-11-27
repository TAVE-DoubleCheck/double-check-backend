package kr.tave.minicore.simulator;

import kr.tave.minicore.simulator.EstimateCalculator.ExampleRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 해약환급금 예시표 조회 (V7 surrender_example_set / surrender_example).
 * 공시값 읽기 전용 2개 표라 JPA 엔티티 없이 JdbcTemplate로 읽는다 (ddl-auto=validate 매핑 추가 불필요).
 */
@Repository
public class SurrenderExampleRepository {

    /** surrender_example_set 한 행 + 그 상품의 예시표 행 전부 */
    public record ExampleSetRow(String productCode, char sex, int joinAge, long sumInsuredWon,
                                int payTermYears, BigDecimal assumedDisclosedRate, int sourcePage,
                                List<ExampleRow> rows) {
    }

    private final JdbcTemplate jdbc;

    public SurrenderExampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExampleSetRow> findByProductCode(String productCode) {
        List<ExampleRow> rows = jdbc.query("""
                        SELECT elapsed_years, paid_premium_won, surrender_won
                          FROM surrender_example WHERE product_code = ? ORDER BY elapsed_years""",
                (rs, i) -> new ExampleRow(rs.getInt("elapsed_years"),
                        rs.getLong("paid_premium_won"), rs.getLong("surrender_won")),
                productCode);
        return jdbc.query("""
                        SELECT product_code, sex, join_age, sum_insured_won, pay_term_years,
                               assumed_disclosed_rate, source_page
                          FROM surrender_example_set WHERE product_code = ?""",
                (rs, i) -> new ExampleSetRow(rs.getString("product_code"), rs.getString("sex").charAt(0),
                        rs.getInt("join_age"), rs.getLong("sum_insured_won"), rs.getInt("pay_term_years"),
                        rs.getBigDecimal("assumed_disclosed_rate"), rs.getInt("source_page"), rows),
                productCode).stream().findFirst();
    }
}
