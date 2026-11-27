package kr.tave.minicore.claim.api;

// 새 파일 (#38): 직원 지급 API. 지금은 지급 실행 1개 (지급 목록은 조회 이슈).
//   /api/admin/** 는 SecurityConfig(BE B)가 ADMIN만 통과시킨다.

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.claim.service.PayoutService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "지급 (직원)", description = "사망보험금 지급")
@RestController
@RequestMapping("/api/admin/payouts")
public class AdminPayoutController {

    private final PayoutService payouts;

    public AdminPayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    @Operation(summary = "지급 실행",
            description = "한 트랜잭션: 지급건 잠금 → PENDING 확인 → 지급건·청구 PAID → 장부 출금 → 계약 TERMINATED. "
                    + "직원 두 명이 동시에 눌러도 한 번만 지급되고, 늦은 쪽은 409 PAYOUT_ALREADY_PAID")
    @PostMapping("/{payoutId}/execute")
    public PayoutExecuteResponse execute(@AuthenticationPrincipal LoginUser staff, @PathVariable long payoutId) {
        return PayoutExecuteResponse.from(payouts.execute(payoutId, staff.id()));
    }
}
