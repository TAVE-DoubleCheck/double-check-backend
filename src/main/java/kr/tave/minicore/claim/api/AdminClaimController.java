package kr.tave.minicore.claim.api;

// 새 파일 (#33): 직원 청구 API. 지금은 사망 청구 대리 접수 1개 (심사·목록은 다음 이슈).
//   /api/admin/** 는 SecurityConfig(BE B)가 ADMIN만 통과시킨다.

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimantRelation;
import kr.tave.minicore.claim.service.ClaimIntakeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "청구 (직원)", description = "사망 청구 대리 접수·심사")
@RestController
@RequestMapping("/api/admin/claims")
public class AdminClaimController {

    private final ClaimIntakeService intake;

    public AdminClaimController(ClaimIntakeService intake) {
        this.intake = intake;
    }

    @Operation(summary = "사망 청구 대리 접수 (직원)",
            description = "유족 대신 직원이 접수. 사망일 당시 계약 상태로 보장 여부를 판단합니다. "
                    + "수익자 일치는 심사 때 확인. 점검 실패 시 409 CLAIM_NOT_ACCEPTABLE")
    @PostMapping
    public ResponseEntity<ClaimResponse> receiveDeath(@AuthenticationPrincipal LoginUser staff,
                                                      @Valid @RequestBody DeathClaimRequest req) {
        Claim claim = intake.receiveDeath(staff, req.contractId(), req.deathDate(), req.claimantName(),
                ClaimantRelation.from(req.claimantRelation()));
        return ResponseEntity.created(URI.create("/api/admin/claims/" + claim.getId()))
                .body(ClaimResponse.from(claim));
    }
}
