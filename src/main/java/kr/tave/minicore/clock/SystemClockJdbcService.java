package kr.tave.minicore.clock;

// 임시 구현. ClockService 소유자는 BE D. 2주차 ClockService 확정 시 이 인터페이스에 맞춰 교체.

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class SystemClockJdbcService implements ClockService {

    private final JdbcTemplate jdbc;

    public SystemClockJdbcService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LocalDate today() {
        // 1행 보장(CHECK id=1). 캐시 없음: 시계가 앞으로 이동하면 즉시 반영돼야 한다.
        return jdbc.queryForObject("SELECT business_date FROM system_clock WHERE id = 1", LocalDate.class);
    }
}
