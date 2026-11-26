package kr.tave.minicore.auth.api;

/**
 * 로그인 성공 응답.
 * 프론트는 accessToken을 보관했다가 요청마다 "Authorization: Bearer {accessToken}" 헤더로 보낸다.
 *
 * @param expiresIn 토큰 유효 시간(초)
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserResponse user
) {
    public static LoginResponse bearer(String token, long expiresInSeconds, UserResponse user) {
        return new LoginResponse(token, "Bearer", expiresInSeconds, user);
    }
}
