package kr.tave.minicore.common.error;

// 변경 (#31): 선택값 details 추가 (『요즘 우아한 백엔드 개발』 24장의 arguments).
//   예: 청구 자동 점검 실패 시 실패 항목 목록 (이슈 #8 CLAIM_CHECK_FAILED).
//   details가 null이면 JSON에서 빠지므로 기존 응답 { code, message } 모양은 그대로다.

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** 공통 오류 응답 본문: { "code": "...", "message": "...", "details": { ... } (선택) } */
@JsonInclude(JsonInclude.Include.NON_NULL) // details 없으면 칸 자체를 생략
public record ErrorResponse(String code, String message, Map<String, Object> details) {

    /** details 없는 응답 (기존 생성 방식 유지). */
    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }

    /** 기존 사용처(GlobalExceptionHandler, SecurityConfig)는 이 형태 그대로 쓴다. */
    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, null);
    }

    /** 추가 정보까지 담는 응답. details가 비어 있으면 생략한다. */
    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorResponse(code.name(), message, details == null || details.isEmpty() ? null : details);
    }
}
