package kr.tave.minicore.auth.security;

import kr.tave.minicore.auth.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JWT 발급·검증 (DB·Docker 없이) */
class JwtProviderTest {

    private static final String SECRET = "test-secret-0123456789-0123456789-abcd";
    private static final Instant T0 = Instant.parse("2026-11-26T12:00:00Z");

    private static JwtProvider at(Instant now) {
        return new JwtProvider(SECRET, Duration.ofHours(2), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void 발급한_토큰을_검증하면_같은_사용자가_나온다() {
        LoginUser user = new LoginUser(7L, "a@test.local", Role.CUSTOMER);

        String token = at(T0).issue(user);

        assertThat(at(T0.plusSeconds(60)).parse(token)).contains(user);
    }

    @Test
    void 유효_시간이_지나면_인증되지_않는다() {
        String token = at(T0).issue(new LoginUser(7L, "a@test.local", Role.CUSTOMER));

        assertThat(at(T0.plus(Duration.ofHours(2)).plusSeconds(1)).parse(token)).isEmpty();
    }

    @Test
    void 다른_키로_서명한_토큰은_인증되지_않는다() {
        String token = new JwtProvider("other-secret-0123456789-0123456789-xyz", Duration.ofHours(2),
                Clock.fixed(T0, ZoneOffset.UTC)).issue(new LoginUser(1L, "x@test.local", Role.ADMIN));

        assertThat(at(T0).parse(token)).isEmpty();
    }

    @Test
    void 토큰_내용을_고치면_인증되지_않는다() {
        String token = at(T0).issue(new LoginUser(7L, "a@test.local", Role.CUSTOMER));
        String[] parts = token.split("\\.");
        // 가운데(내용) 한 글자만 바꿔 서명과 맞지 않게 만든다
        char c = parts[1].charAt(5);
        parts[1] = parts[1].substring(0, 5) + (c == 'A' ? 'B' : 'A') + parts[1].substring(6);

        assertThat(at(T0).parse(String.join(".", parts))).isEmpty();
        assertThat(at(T0).parse("not-a-jwt")).isEmpty();
    }

    @Test
    void 서명_키가_32바이트보다_짧으면_기동하지_않는다() {
        assertThatThrownBy(() -> new JwtProvider("short", Duration.ofHours(2), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }
}
