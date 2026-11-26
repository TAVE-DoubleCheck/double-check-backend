package kr.tave.minicore.daily;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DailyWorkService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate dayTransaction;
    private final Map<DailyWorkStage, DailyWorkStep> steps = new EnumMap<>(DailyWorkStage.class);

    public DailyWorkService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            List<DailyWorkStep> registeredSteps) {
        this.jdbc = jdbc;
        this.dayTransaction = new TransactionTemplate(transactionManager);
        this.dayTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (DailyWorkStep step : registeredSteps) {
            DailyWorkStage stage = Objects.requireNonNull(step.stage(), "Daily work stage is required");
            if (steps.putIfAbsent(stage, step) != null) {
                throw new IllegalArgumentException("Duplicate daily work stage: " + stage);
            }
        }
    }

    /**
     * 목표 날짜까지 하루씩 처리한다. 같은 날짜는 아무 작업도 하지 않는다.
     * 전체 기간을 묶는 외부 트랜잭션은 허용하지 않는다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public LocalDate advanceTo(LocalDate targetDate) {
        Objects.requireNonNull(targetDate, "Target date is required");
        boolean firstDay = true;
        while (true) {
            boolean rejectPast = firstDay;
            boolean advanced = Boolean.TRUE.equals(dayTransaction.execute(status -> {
                // 매일 잠금을 얻은 후 다시 읽는다. 다른 요청이 처리한 날짜를 중복 실행하지 않는다.
                LocalDate current = jdbc.queryForObject(
                        "SELECT business_date FROM system_clock WHERE id = 1 FOR UPDATE", LocalDate.class);
                if (current == null) {
                    throw new IllegalStateException("Business date is missing");
                }
                if (rejectPast && targetDate.isBefore(current)) {
                    throw new IllegalArgumentException("Target date cannot be before business date");
                }
                if (!current.isBefore(targetDate)) {
                    return false;
                }
                requireAllSteps();
                LocalDate next = current.plusDays(1);
                try {
                    jdbc.update("UPDATE system_clock SET business_date = ? WHERE id = 1", next);
                    for (DailyWorkStage stage : DailyWorkStage.values()) {
                        steps.get(stage).execute(next);
                    }
                } catch (RuntimeException exception) {
                    throw new DailyWorkException(next, exception);
                }
                return true;
            }));
            if (!advanced) {
                return targetDate;
            }
            firstDay = false;
        }
    }

    private void requireAllSteps() {
        for (DailyWorkStage stage : DailyWorkStage.values()) {
            if (!steps.containsKey(stage)) {
                throw new IllegalStateException("Daily work stage is not implemented: " + stage);
            }
        }
    }
}
