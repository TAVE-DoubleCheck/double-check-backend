package kr.tave.minicore.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** application.yml의 auth.* 설정 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(Jwt jwt, Admin admin, Cors cors) {

    /**
     * @param secret    HS256 서명 키 (32바이트 이상)
     * @param expiresIn 토큰 유효 시간 (실제 시각 기준)
     */
    public record Jwt(String secret, Duration expiresIn) {
    }

    /** 기동 시 자동 생성할 직원 계정 */
    public record Admin(boolean enabled, String email, String password, String name) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
