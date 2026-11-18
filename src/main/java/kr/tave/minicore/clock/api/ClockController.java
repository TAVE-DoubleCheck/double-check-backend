package kr.tave.minicore.clock.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@Tag(name = "시스템 날짜", description = "로그인 사용자의 업무 날짜 조회")
@RestController
@RequestMapping("/api/clock")
public class ClockController {

    private final ClockService clock;

    public ClockController(ClockService clock) {
        this.clock = clock;
    }

    @Operation(summary = "현재 시스템 날짜 조회", description = "고객과 직원 모두 로그인 후 조회할 수 있습니다.")
    @GetMapping
    public ResponseEntity<ClockResponse> today(Principal principal) {
        // The authentication module must supply the authenticated request principal.
        // An Authorization header alone is not proof of authentication.
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ClockResponse(clock.today()));
    }
}
