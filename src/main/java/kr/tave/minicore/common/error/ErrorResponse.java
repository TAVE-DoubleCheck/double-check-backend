package kr.tave.minicore.common.error;

/** 공통 오류 응답 본문: { "code": "...", "message": "..." } */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message);
    }
}
