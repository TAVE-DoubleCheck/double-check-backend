package kr.tave.minicore.common.error;

// 변경 (#31): 응답에 추가 정보를 실을 수 있도록 details 생성자 추가. 기존 생성자는 그대로 (details 없음).

import java.util.Map;

/**
 * 오류 코드 + 사용자에게 보여 줄 메시지 (+ 선택 추가 정보). GlobalExceptionHandler가 ErrorResponse로 변환한다.
 * 클라이언트 잘못이 명확할 때만 던진다. IllegalArgumentException 같은 범용 예외는 서버 버그로도 나므로 500으로 둔다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details; // 없으면 null

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    /** 추가 정보를 함께 응답할 때 사용. 예: Map.of("failedChecks", List.of("COVERAGE_PERIOD")) */
    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? null : Map.copyOf(details); // 던진 뒤 바뀌지 않도록 복사
    }

    public ErrorCode code() {
        return code;
    }

    /** 추가 정보. 없으면 null. */
    public Map<String, Object> details() {
        return details;
    }
}
