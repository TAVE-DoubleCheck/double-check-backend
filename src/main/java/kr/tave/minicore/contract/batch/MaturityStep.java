package kr.tave.minicore.contract.batch;

import kr.tave.minicore.daily.DailyWorkStage;
import kr.tave.minicore.daily.DailyWorkStep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 매일 작업 4단계: 만기.
 * 1단계에서 가입할 수 있는 상품은 종신(insure_term_code '99')뿐이라 보험기간이 끝나는 계약이 없다.
 * 그래서 하는 일은 "종신이 아닌 계약이 들어와 있지 않은지" 확인하는 것뿐이다.
 * 정기보험 등을 열면 그때 만기 처리(만기 상태·만기 환급)를 구현하고 이 확인을 지운다.
 */
@Component
@ConditionalOnProperty(name = "daily.contract-steps.enabled", havingValue = "true", matchIfMissing = true)
public class MaturityStep implements DailyWorkStep {

    private final JdbcTemplate jdbc;

    public MaturityStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DailyWorkStage stage() {
        return DailyWorkStage.MATURITY;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(LocalDate businessDate) {
        Integer nonWholeLife = jdbc.queryForObject(
                "SELECT count(*) FROM contract WHERE insure_term_code <> '99'", Integer.class);
        if (nonWholeLife != null && nonWholeLife > 0) {
            // 조용히 넘어가면 만기 계약을 놓치므로 그날 작업 전체를 실패시킨다
            throw new IllegalStateException("만기 처리가 없는 비종신 계약 " + nonWholeLife + "건이 있습니다.");
        }
    }
}
