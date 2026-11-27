package kr.tave.minicore.common.error;

// 공통 오류 처리 단위 테스트 (DB·Docker 없이 MockMvc standalone).
// 요청 본문 오류가 500이 아니라 400 VALIDATION_ERROR로, ApiException은 지정한 코드·상태로 나가는지 확인한다.
// 추가 (#31): IllegalArgumentException 500 유지, 낙관적 락 409, 잘못된 메서드 405·Content-Type 415, details 응답·생략.
//   실제 보안 설정을 거친 404·405·415는 ErrorStatusIntegrationTest에서 확인.

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    /**
     * #31: IllegalArgumentException은 서버 버그로도 나므로 400으로 바꾸지 않는다 (책 24장).
     * 나중에 누가 IAE → 400 매핑을 추가하면 이 테스트가 깨진다.
     */
    @Test
    void IllegalArgumentException은_500_INTERNAL_ERROR() throws Exception {
        mvc.perform(post("/test/iae"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류")); // 내부 메시지는 응답에 안 나감
    }

    /** #31: @Version 충돌은 다시 시도하면 되는 상황이라 409. */
    @Test
    void 낙관적_락_충돌은_409_CONCURRENT_UPDATE() throws Exception {
        mvc.perform(post("/test/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    /** #31: POST 전용 주소에 GET → 500이 아니라 405 + Allow 헤더. */
    @Test
    void 잘못된_메서드는_405_METHOD_NOT_ALLOWED() throws Exception {
        mvc.perform(get("/test/paid"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /** #31: JSON 본문 주소에 text/plain → 500이 아니라 415. */
    @Test
    void 지원하지_않는_Content_Type은_415_UNSUPPORTED_MEDIA_TYPE() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /** #31: ApiException에 details를 넣으면 응답에 그대로 나간다. */
    @Test
    void details가_있으면_응답에_포함() throws Exception {
        mvc.perform(post("/test/details"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONTRACT_STATUS"))
                .andExpect(jsonPath("$.details.failedChecks[0]").value("COVERAGE_PERIOD"));
    }

    /** #31: details가 없으면 칸 자체가 빠져 기존 { code, message } 모양 유지. */
    @Test
    void details가_없으면_칸이_생략됨() throws Exception {
        mvc.perform(post("/test/paid"))
                .andExpect(jsonPath("$.details").doesNotExist());
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

        /** #31: 서버 쪽 IAE (예: 적재 데이터 형식 오류) 흉내. */
        @PostMapping("/test/iae")
        String iae() {
            throw new IllegalArgumentException("보험료 형식이 '숫자원'이 아님");
        }

        /** #31: JPA @Version 충돌 흉내. */
        @PostMapping("/test/lock")
        String lock() {
            throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
        }

        /** #31: details를 담은 ApiException. */
        @PostMapping("/test/details")
        String details() {
            throw new ApiException(ErrorCode.INVALID_CONTRACT_STATUS, "점검 실패",
                    Map.of("failedChecks", List.of("COVERAGE_PERIOD")));
        }
    }
}
