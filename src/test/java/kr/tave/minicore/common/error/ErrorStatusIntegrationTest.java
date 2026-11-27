package kr.tave.minicore.common.error;

// 새 파일 (#31): 실제 앱 설정(보안 필터 + 정적 리소스 처리 포함)에서 스프링이 거절하는 요청의 응답 확인.
//   전에는 로그인한 사용자가 없는 주소·잘못된 메서드를 부르면 GlobalExceptionHandler가 500으로 바꿨다.
//   MockMvc standalone(GlobalExceptionHandlerTest)에서는 없는 주소가 NoResourceFoundException이 아니라
//   다른 예외로 나므로, 실제 경로는 여기서 확인한다.

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class ErrorStatusIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    /** 로그인한 상태로 없는 주소 → 404 NOT_FOUND (전에는 500). */
    @Test
    void 로그인_상태에서_없는_주소는_404() throws Exception {
        mvc.perform(get("/api/no-such-path").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** GET 전용 주소(/api/auth/me)에 POST → 405 + Allow 헤더 (전에는 500). */
    @Test
    void 로그인_상태에서_잘못된_메서드는_405() throws Exception {
        mvc.perform(post("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /** JSON 주소(로그인)에 text/plain → 415 (전에는 500). 공개 주소라 토큰 없이 확인. */
    @Test
    void 지원하지_않는_Content_Type은_415() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /** 토큰 없이 없는 주소 → 보안 필터가 먼저 막아 401 (기존 동작 유지 확인). */
    @Test
    void 토큰_없이_없는_주소는_기존대로_401() throws Exception {
        mvc.perform(get("/api/no-such-path"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** 시작 시 만들어지는 직원 계정으로 로그인해 "Bearer 토큰" 문자열을 만든다. */
    private String bearer() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"admin-pass-1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).get("accessToken").asText();
    }
}
