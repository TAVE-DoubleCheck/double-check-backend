package kr.tave.minicore.common.error;

// 변경: 요청 본문(JSON) 오류도 400 VALIDATION_ERROR로 응답하도록 핸들러 3개 추가.
//       전에는 쿼리 파라미터 오류만 400이고, 본문 필수값 누락·JSON 깨짐은 아래 unexpected()로 떨어져 500이 났다.
//
// 변경 (#31): 4xx·5xx 구분 보완 (『요즘 우아한 백엔드 개발』 24장 「IllegalArgumentException은 400인가?」).
//   - unexpected(): 스프링 기본 예외(없는 주소·잘못된 메서드·Content-Type 등)는 원래 상태(404·405·415)로 응답.
//     전에는 전부 500이라 클라이언트 잘못이 서버 장애처럼 보였다.
//   - 낙관적 락 충돌(@Version) → 409 CONCURRENT_UPDATE (전에는 500)
//   - ApiException의 details를 응답에 실음
//   - JSON 읽기 실패 원인을 warn 로그로 남김 (응답에는 계속 숨김)
//   - INTERNAL_ERROR 문자열 → ErrorCode.INTERNAL_ERROR
//   IllegalArgumentException은 일부러 매핑하지 않는다: 서버 버그(적재 Normalizer 등)로도 나므로 500이 맞다.

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatusCode;
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
                .body(ErrorResponse.of(e.code(), e.getMessage(), e.details())); // #31: details 있으면 같이 응답
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
        // #31: 응답에는 숨기는 대신 서버 로그에 원인을 남긴다. 클라이언트 잘못(4xx)이라 error가 아닌 warn
        log.warn("요청 본문 읽기 실패: {}", e.getMostSpecificCause().getMessage());
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

    /**
     * #31: 낙관적 락 충돌 → 409 CONCURRENT_UPDATE.
     * 두 요청이 같은 행(@Version이 있는 계약·청구서 등)을 동시에 바꾸면 늦은 쪽에서 난다.
     * 서버 고장이 아니라 "다른 요청이 먼저 바꿈"이라 다시 조회 후 재시도하면 된다.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> concurrentUpdate(OptimisticLockingFailureException e) {
        log.warn("동시 수정 충돌: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.CONCURRENT_UPDATE.status())
                .body(ErrorResponse.of(ErrorCode.CONCURRENT_UPDATE, "다른 요청이 먼저 수정했습니다. 다시 조회한 뒤 시도하세요."));
    }

    /**
     * 위에서 처리하지 않은 예외.
     * #31: 스프링 기본 예외는 자기 상태 코드를 갖고 있다(org.springframework.web.ErrorResponse).
     *   예: 없는 주소 404, 잘못된 메서드 405, Content-Type 415. 이 상태를 그대로 쓰고 서버 오류 로그는 남기지 않는다.
     *   그 밖의 예외(IllegalArgumentException·NPE·DB 오류 등)는 500 + error 로그.
     *   스프링 예외라도 5xx면 서버 문제라 error 로그를 남긴다 (상태는 스프링 값 유지).
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse springError
                && springError.getStatusCode().is4xxClientError()) {
            return springClientError(springError);
        }
        log.error("처리되지 않은 예외", e);
        // 스프링 예외가 5xx(예: 503)를 지정했으면 그 상태를 유지하고, 그 외는 500
        HttpStatusCode status = e instanceof org.springframework.web.ErrorResponse springServerError
                ? springServerError.getStatusCode() : ErrorCode.INTERNAL_ERROR.status();
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "서버 내부 오류"));
    }

    /**
     * #31: 스프링이 거절한 4xx 요청을 우리 오류 형식으로 바꾼다.
     * 상태와 헤더(405의 Allow, 415의 Accept 등)는 스프링 값을 그대로 쓰고, code·message만 우리 것으로 채운다.
     * 따로 코드를 두지 않은 4xx(406, 413 업로드 초과 등)는 상태는 유지하고 code만 VALIDATION_ERROR로 묶는다.
     */
    private static ResponseEntity<ErrorResponse> springClientError(org.springframework.web.ErrorResponse springError) {
        HttpStatusCode status = springError.getStatusCode();
        log.debug("요청 거절 {}: {}", status.value(), springError.getBody().getDetail()); // 4xx라 경보 대상 아님
        // 숫자로 비교: HttpStatus enum에 없는 비표준 코드가 와도 default로 처리되도록
        ErrorResponse body = switch (status.value()) {
            case 404 -> ErrorResponse.of(ErrorCode.NOT_FOUND, "요청한 주소를 찾을 수 없습니다.");
            case 405 -> ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED, "이 주소에서 지원하지 않는 HTTP 메서드입니다.");
            case 415 -> ErrorResponse.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다.");
            default -> ErrorResponse.of(ErrorCode.VALIDATION_ERROR, "요청을 처리할 수 없습니다.");
        };
        return ResponseEntity.status(status).headers(springError.getHeaders()).body(body);
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
