package kr.tave.minicore.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 공통 오류 코드 (API 표 10장).
 *  400 = 입력 형식 오류 / 404 = 리소스 없음 / 409 = 상태 충돌
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    NO_EFFECTIVE_VERSION(HttpStatus.CONFLICT),
    PRODUCT_NOT_SUBSCRIBABLE(HttpStatus.CONFLICT),
    RATE_NOT_FOUND(HttpStatus.CONFLICT),
    AMOUNT_NOT_UNIT_MULTIPLE(HttpStatus.BAD_REQUEST),
    AMOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    BIRTH_DATE_IN_FUTURE(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
