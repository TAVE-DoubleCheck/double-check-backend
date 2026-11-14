package kr.tave.minicore.common.error;

// 변경: 요청 본문(JSON) 오류도 400 VALIDATION_ERROR로 응답하도록 핸들러 3개 추가.
//       전에는 쿼리 파라미터 오류만 400이고, 본문 필수값 누락·JSON 깨짐은 아래 unexpected()로 떨어져 500이 났다.

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> apiException(ApiException e) {
        return ResponseEntity.status(e.code().status())
                .body(ErrorResponse.of(e.code(), e.getMessage()));
    }

    /** 필수 쿼리 파라미터 누락 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> missingParam(MissingServletRequestParameterException e) {
        return validationError("필수 파라미터 누락: " + e.getParameterName());
    }

    /** 날짜 형식·숫자 형식·enum(M|W) 변환 실패 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return validationError("파라미터 형식 오류: " + e.getName() + "=" + e.getValue());
    }

    /**
     * 요청 본문(@RequestBody @Valid) 검증 실패 → 400.
     * 예: 청구 거절에 reason 없음. 어떤 칸이 틀렸는지 message에 "칸 이름(이유)"로 모아 준다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining(", "));
        return validationError("요청 본문 오류: " + fields);
    }

    /**
     * 요청 본문을 읽지 못함 → 400.
     * 예: JSON 문법이 깨짐, 없는 enum 값(type: "ABC"), 날짜 형식 오류.
     * 원인 문구(Jackson 내부 메시지)는 클래스 이름이 섞여 있어 응답에 그대로 내보내지 않는다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        return validationError("요청 본문 형식 오류 (JSON 문법·값 형식을 확인하세요)");
    }

    /**
     * 쿼리·경로 파라미터에 붙인 검증(@Min, @NotBlank 등) 실패 → 400.
     * validation 의존성을 추가하면서 생기는 예외라 같이 막는다. 없으면 500으로 떨어진다.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> invalidParam(HandlerMethodValidationException e) {
        String params = e.getParameterValidationResults().stream()
                .map(r -> r.getMethodParameter().getParameterName() + "("
                        + r.getResolvableErrors().stream()
                                .map(err -> err.getDefaultMessage())
                                .collect(Collectors.joining(", "))
                        + ")")
                .collect(Collectors.joining(", "));
        return validationError("파라미터 검증 실패: " + params);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_ERROR", "서버 내부 오류"));
    }

    private static ResponseEntity<ErrorResponse> validationError(String message) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, message));
    }

    /** 칸 하나의 검증 오류를 "칸이름(이유)" 문자열로 만든다. */
    private static String describe(FieldError f) {
        return f.getField() + "(" + f.getDefaultMessage() + ")";
    }
}
