package kr.tave.minicore.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 공통 오류 코드 (API 표 10장).
 *  400 = 입력 형식 오류 / 404 = 리소스 없음 / 409 = 상태 충돌
 */
public enum ErrorCode {

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    NO_EFFECTIVE_VERSION(HttpStatus.CONFLICT),
    PRODUCT_NOT_SUBSCRIBABLE(HttpStatus.CONFLICT),
    RATE_NOT_FOUND(HttpStatus.CONFLICT),
    AMOUNT_NOT_UNIT_MULTIPLE(HttpStatus.BAD_REQUEST),
    AMOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    BIRTH_DATE_IN_FUTURE(HttpStatus.BAD_REQUEST),

    // 계약 (BE B). CONTRACT_NOT_FOUND는 청구(BE C)와 같은 이름을 쓴다 (이슈 #8): 없는 계약·내 계약 아님 모두 404
    CONTRACT_NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID_CONTRACT_STATUS(HttpStatus.CONFLICT),

    // 지급 (API v0.4 9장에서 확정): 이미 지급된 건을 다시 실행하면 409. 화면은 회색 안내로 표시
    PAYOUT_ALREADY_PAID(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
