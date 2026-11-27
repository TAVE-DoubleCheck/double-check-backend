package kr.tave.minicore.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 공통 오류 코드 (API 표 10장).
 *  400 = 입력 형식 오류 / 401 = 로그인 필요 / 403 = 권한 없음 / 404 = 리소스 없음 / 409 = 상태 충돌
 *  405·415 = 스프링이 거절한 요청 / 500 = 서버 오류
 *
 * 변경 (#31): 공통 코드 5개 추가 (NOT_FOUND, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE, CONCURRENT_UPDATE, INTERNAL_ERROR).
 *   클라이언트 잘못인데 500으로 나가던 경우를 4xx로 구분하기 위함 (『요즘 우아한 백엔드 개발』 24장).
 */
public enum ErrorCode {

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),

    // 로그인 (BE B). 이메일이 없을 때와 비밀번호가 틀릴 때 같은 코드를 쓴다 (가입 여부를 알려 주지 않기 위해)
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT),

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
    // 가입 시 화면에서 본 상품 버전·월 보험료가 서버 재계산 결과와 다름 → 다시 계산해서 보여 주고 확인받기
    QUOTE_CHANGED(HttpStatus.CONFLICT),
    // 납부 (BE B): 없는 청구서·내 청구서 아님 404 / 이미 낸 청구서 409 / 낼 수 없는 청구서(앞 회차 미납, 마감 지남, 계약 상태) 409
    BILL_NOT_FOUND(HttpStatus.NOT_FOUND),
    BILL_ALREADY_PAID(HttpStatus.CONFLICT),
    BILL_NOT_PAYABLE(HttpStatus.CONFLICT),

    // 청구 (BE C, #33): 접수 단계 자동 점검 실패. 실패 항목은 details.failedChecks (이슈 #8 초안 이름)
    CLAIM_NOT_ACCEPTABLE(HttpStatus.CONFLICT),
    // 청구 (BE C, #35): 심사·승인·거절
    CLAIM_NOT_FOUND(HttpStatus.NOT_FOUND),       // 없는 청구
    INVALID_CLAIM_STATUS(HttpStatus.CONFLICT),   // 지금 상태에서 안 되는 요청 (예: 접수 상태에서 승인)
    CLAIM_CHECK_FAILED(HttpStatus.CONFLICT),     // 승인 시 자동 점검 실패. 실패 항목은 details.failedChecks

    // 지급 (API v0.4 9장에서 확정): 이미 지급된 건을 다시 실행하면 409. 화면은 회색 안내로 표시
    PAYOUT_ALREADY_PAID(HttpStatus.CONFLICT),
    PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND), // #38: 없는 지급건

    // 공통 (#31): 스프링이 컨트롤러 전에 거절하는 요청. 전에는 전부 500 INTERNAL_ERROR로 나갔다
    NOT_FOUND(HttpStatus.NOT_FOUND),                           // 없는 주소
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),         // 주소는 있지만 메서드가 다름 (GET 주소에 POST)
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE), // 지원하지 않는 Content-Type
    // 공통 (#31): 다른 요청이 같은 행을 먼저 바꿈 (JPA @Version). 다시 조회 후 재시도하면 되는 상황이라 409
    CONCURRENT_UPDATE(HttpStatus.CONFLICT),
    // 공통 (#31): 예상하지 못한 서버 오류. 전에는 GlobalExceptionHandler에 문자열로만 있었다
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
