package kr.tave.minicore.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.tave.minicore.auth.domain.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 발급·검증.
 * 토큰 안: sub = 회원 id, email, role. 서명 = HS256.
 *
 * 만료 시각은 실제 시각(Clock) 기준이다. 업무 날짜(system_clock)는 시뮬레이션용이라
 * 10년 뒤로 옮겨도 로그인이 풀리면 안 되므로 쓰지 않는다.
 */
@Component
public class JwtProvider {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration expiresIn;
    private final Clock clock;

    @Autowired
    public JwtProvider(AuthProperties properties) {
        this(properties.jwt().secret(), properties.jwt().expiresIn(), Clock.systemUTC());
    }

    /** 테스트용: 시각을 고정해 만료를 확인한다 */
    JwtProvider(String secret, Duration expiresIn, Clock clock) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "auth.jwt.secret(JWT_SECRET)은 32바이트 이상이어야 합니다. 현재 " + bytes.length + "바이트");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expiresIn = expiresIn;
        this.clock = clock;
    }

    public String issue(LoginUser user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("email", user.email())
                .claim("role", user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiresIn)))
                .signWith(key)
                .compact();
    }

    /** 서명이 맞고 만료 전이면 로그인 사용자, 아니면 빈 값 (이유는 구분하지 않고 401로 처리) */
    public Optional<LoginUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new LoginUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("email", String.class),
                    Role.valueOf(claims.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // 서명 불일치·만료·형식 오류·없는 role 값 모두 "인증 안 됨"
            return Optional.empty();
        }
    }

    public Duration expiresIn() {
        return expiresIn;
    }
}
