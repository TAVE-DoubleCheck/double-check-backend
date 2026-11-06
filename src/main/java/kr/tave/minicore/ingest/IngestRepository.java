package kr.tave.minicore.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;

/**
 * ingest_log, raw_api_response, system_clock 접근.
 * 트랜잭션 밖(자동 커밋)에서 호출된다 → 데이터 적재가 롤백돼도 실행 기록과 원문은 남는다.
 */
@Repository
public class IngestRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public IngestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long startLog(String api) {
        return jdbc.queryForObject(
                "INSERT INTO ingest_log(api, status) VALUES (?, 'RUNNING') RETURNING id", Long.class, api);
    }

    public void finishLog(long id, boolean success, int callCount, int rowCount, String message) {
        jdbc.update("""
                UPDATE ingest_log
                   SET status = ?, finished_at = now(), call_count = ?, row_count = ?, message = ?
                 WHERE id = ?""",
                success ? "SUCCESS" : "FAILED", callCount, rowCount, truncate(message), id);
    }

    public void saveRaw(long logId, String api, Map<String, String> requestParams, String body) {
        jdbc.update("""
                INSERT INTO raw_api_response(ingest_log_id, api, request_params, response_body)
                VALUES (?, ?, ?::jsonb, ?::jsonb)""",
                logId, api, toJson(requestParams), asJsonDocument(body));
    }

    /** 업무 기준일. LocalDate.now()를 쓰지 않는다. */
    public LocalDate businessDate() {
        return jdbc.queryForObject("SELECT business_date FROM system_clock WHERE id = 1", LocalDate.class);
    }

    public long count(String table) {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException(table);
        }
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    /** JSON이면 그대로, 아니면(XML 등) JSON 문자열로 감싸서 저장 */
    private String asJsonDocument(String body) {
        try {
            mapper.readTree(body);
            return body;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return toJson(body);
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 4000 ? s.substring(0, 4000) + "…" : s;
    }
}
