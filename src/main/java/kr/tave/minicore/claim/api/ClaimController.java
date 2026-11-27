package kr.tave.minicore.claim.api;

// 새 파일 (#33): 고객 청구 API. 지금은 장해 청구 접수 1개 (내 청구 목록·서류는 다음 이슈).

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.service.ClaimIntakeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "청구", description = "보험금 청구 (고객)")
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private final ClaimIntakeService intake;

    public ClaimController(ClaimIntakeService intake) {
        this.intake = intake;
    }

    @Operation(summary = "장해 청구 접수 (고객)",
            description = "내 계약만. 사고일 당시 계약 상태로 보장 여부를 판단합니다. "
                    + "점검 실패 시 409 CLAIM_NOT_ACCEPTABLE, 실패 항목은 details.failedChecks")
    @PostMapping
    public ResponseEntity<ClaimResponse> receiveDisability(@AuthenticationPrincipal LoginUser me,
                                                           @Valid @RequestBody DisabilityClaimRequest req) {
        Claim claim = intake.receiveDisability(me, req.contractId(), req.accidentDate(), req.accidentDescription());
        return ResponseEntity.created(URI.create("/api/claims/" + claim.getId())).body(ClaimResponse.from(claim));
    }
}
