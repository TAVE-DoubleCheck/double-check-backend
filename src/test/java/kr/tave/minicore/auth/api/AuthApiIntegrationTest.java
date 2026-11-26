package kr.tave.minicore.auth.api;

// 로그인 API 통합 테스트.
// Testcontainers PostgreSQL + 실제 보안 설정(MockMvc)으로 회원가입 → 로그인 → 토큰으로 내 정보,
// 그리고 401(토큰 없음·틀림) / 403(고객이 직원 주소) / 공개 주소가 막히지 않는지 확인한다.

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key",
        "auth.admin.email=admin@test.local",
        "auth.admin.password=admin-pass-1234"
})
@AutoConfigureMockMvc
@Testcontainers
class AuthApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final String PASSWORD = "password-1234";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void 회원가입하면_201이고_비밀번호는_BCrypt로_저장된다() throws Exception {
        String email = uniqueEmail();

        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupJson(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.birthDate").value("1996-10-01"))
                .andExpect(jsonPath("$.sex").value("M"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    void 같은_이메일은_대소문자가_달라도_409() throws Exception {
        String email = uniqueEmail();
        signup(email);

        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void 형식이_틀린_가입_요청은_400() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"not-email","password":"short","name":"","birthDate":"1996-10-01","sex":"M"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 로그인하면_토큰을_주고_그_토큰으로_내_정보를_본다() throws Exception {
        String email = uniqueEmail();
        signup(email);

        String token = login(email, PASSWORD);

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("김가입"));
    }

    @Test
    void 비밀번호가_틀리거나_없는_이메일이면_같은_401() throws Exception {
        String email = uniqueEmail();
        signup(email);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(uniqueEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 토큰이_없거나_틀리면_401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 고객이_직원_주소를_부르면_403() throws Exception {
        String email = uniqueEmail();
        signup(email);
        String token = login(email, PASSWORD);

        mvc.perform(get("/api/admin/claims").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 기동_시_만든_직원_계정으로_로그인하면_ADMIN이다() throws Exception {
        String token = login("admin@test.local", "admin-pass-1234");

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void 상품_조회는_로그인_없이_된다() throws Exception {
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }

    private void signup(String email) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupJson(email)))
                .andExpect(status().isCreated());
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("accessToken").asText();
    }

    private static String uniqueEmail() {
        return "u-" + UUID.randomUUID() + "@test.local";
    }

    private static String signupJson(String email) {
        return """
                {"email":"%s","password":"%s","name":"김가입","birthDate":"1996-10-01","sex":"M"}
                """.formatted(email, PASSWORD);
    }

    private static String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }
}
