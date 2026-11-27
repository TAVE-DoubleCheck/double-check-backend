package kr.tave.minicore.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.common.error.ErrorResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 어떤 주소를 누가 부를 수 있는지 정하는 곳 (팀 전체 API에 영향).
 *
 *  공개      : 회원가입·로그인, 상품 조회·보험료 계산(GET /api/products/**), Swagger, 헬스체크
 *  직원 전용 : /api/admin/**  (청구 심사, 지급 실행, 날짜 이동 등)
 *  그 외     : 로그인 필요 (고객·직원 모두)
 *
 * 새 공개 주소가 필요하면 PUBLIC_GET / PUBLIC_POST에 추가하고 BE B에게 알려 주세요.
 */
@Configuration
public class SecurityConfig {

    /** 로그인 없이 GET 가능 */
    private static final String[] PUBLIC_GET = {
            "/",
            "/api/products", "/api/products/**",
            // 예상 계산 시뮬레이터: 가입 예정자(비로그인)용. 명세 3장 — 팀 확인 전 임시 공개 (확인 필요 목록 #1)
            "/api/simulator/estimate",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
            "/actuator/health"
    };

    /** 로그인 없이 POST 가능 */
    private static final String[] PUBLIC_POST = {
            "/api/auth/signup",
            "/api/auth/login"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider, ObjectMapper objectMapper)
            throws Exception {
        http
                // 토큰 방식이라 서버 세션·쿠키를 쓰지 않는다 → CSRF 대상 아님
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> { })
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 오류 응답을 만드는 내부 주소. 막으면 404·500도 전부 401로 보인다
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        // 토큰 없음·만료·위조 → 401
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, objectMapper,
                                ErrorCode.UNAUTHORIZED, "로그인이 필요합니다."))
                        // 로그인은 했지만 권한 없음 (고객이 직원 주소 호출) → 403
                        .accessDeniedHandler((req, res, ex) -> writeError(res, objectMapper,
                                ErrorCode.FORBIDDEN, "권한이 없습니다.")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = properties.cors() == null || properties.cors().allowedOrigins() == null
                ? List.of() : properties.cors().allowedOrigins();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** GlobalExceptionHandler와 같은 { code, message } 형식. 필터 단계라 ControllerAdvice가 닿지 않아 직접 쓴다 */
    private static void writeError(HttpServletResponse res, ObjectMapper objectMapper, ErrorCode code, String message)
            throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(res.getWriter(), ErrorResponse.of(code, message));
    }
}
