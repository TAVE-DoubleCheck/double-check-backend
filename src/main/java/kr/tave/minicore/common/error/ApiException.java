package kr.tave.minicore.common.error;

/** 오류 코드 + 사용자에게 보여 줄 메시지. GlobalExceptionHandler가 ErrorResponse로 변환한다. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
