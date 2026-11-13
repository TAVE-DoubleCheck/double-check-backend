package kr.tave.minicore.common.error;

// 공통 오류 처리 단위 테스트 (DB·Docker 없이 MockMvc standalone).
// 요청 본문 오류가 500이 아니라 400 VALIDATION_ERROR로, ApiException은 지정한 코드·상태로 나가는지 확인한다.

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    /** 테스트마다 가짜 컨트롤러 + 실제 GlobalExceptionHandler로 MockMvc를 만든다. */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 본문 필수값(reason)이 비면 400 + 어떤 칸이 틀렸는지 message에 담긴다. */
    @Test
    void 본문_필수값_누락은_400_VALIDATION_ERROR() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\",\"type\":\"DEATH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("reason")));
    }

    /** JSON 문법이 깨지면 500이 아니라 400. */
    @Test
    void JSON_문법_오류는_400_VALIDATION_ERROR() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 없는 enum 값(type=ABC)도 본문을 읽지 못한 것이라 400. */
    @Test
    void 없는_enum_값은_400_VALIDATION_ERROR() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"사유\",\"type\":\"ABC\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 쿼리 파라미터에 붙인 @Min 위반도 400 (validation 의존성 추가로 새로 생기는 경우). */
    @Test
    void 파라미터_검증_실패는_400_VALIDATION_ERROR() throws Exception {
        mvc.perform(post("/test/param").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("size")));
    }

    /** 정상 본문은 그대로 통과한다 (핸들러가 정상 요청을 막지 않는지). */
    @Test
    void 정상_본문은_200() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"사유\",\"type\":\"DEATH\"}"))
                .andExpect(status().isOk());
    }

    /** ApiException(PAYOUT_ALREADY_PAID)은 409 + 코드·메시지가 그대로 나간다. */
    @Test
    void 이미_지급된_건은_409_PAYOUT_ALREADY_PAID() throws Exception {
        mvc.perform(post("/test/paid"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYOUT_ALREADY_PAID"))
                .andExpect(jsonPath("$.message").value("다른 직원이 이미 지급했습니다"));
    }

    /** 테스트용 요청 본문: 칸 2개 모두 필수. */
    record TestBody(@NotBlank String reason, @NotNull TestType type) {
    }

    /** 테스트용 enum (없는 값 거절 확인용). */
    enum TestType { DEATH, DISABILITY }

    /** 오류 상황을 일부러 만드는 가짜 컨트롤러. */
    @RestController
    static class TestController {

        /** 본문 검증 확인용. */
        @PostMapping("/test/body")
        String body(@Valid @RequestBody TestBody body) {
            return "ok";
        }

        /** 파라미터 검증 확인용. */
        @PostMapping("/test/param")
        String param(@RequestParam @Min(1) int size) {
            return "ok";
        }

        /** ApiException 변환 확인용. */
        @PostMapping("/test/paid")
        String paid() {
            throw new ApiException(ErrorCode.PAYOUT_ALREADY_PAID, "다른 직원이 이미 지급했습니다");
        }
    }
}
