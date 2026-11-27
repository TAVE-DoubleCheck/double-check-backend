package kr.tave.minicore.claim.api;

// 새 파일 (#33): 직원 청구 API. 사망 청구 대리 접수.
// 변경 (#35): 심사 시작·장해지급률 판정 기록·승인·거절 4개 추가.
//   /api/admin/** 는 SecurityConfig(BE B)가 ADMIN만 통과시킨다. 처리 직원은 로그인 계정으로 기록.

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimAssessment;
import kr.tave.minicore.claim.domain.ClaimantRelation;
import kr.tave.minicore.claim.service.ClaimIntakeService;
import kr.tave.minicore.claim.service.ClaimReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ClaimReviewService review;

    public AdminClaimController(ClaimIntakeService intake, ClaimReviewService review) {
        this.intake = intake;
        this.review = review;
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

    @Operation(summary = "심사 시작", description = "RECEIVED → REVIEWING. 그 밖의 상태면 409 INVALID_CLAIM_STATUS")
    @PostMapping("/{claimId}/review")
    public ClaimResponse startReview(@AuthenticationPrincipal LoginUser staff, @PathVariable long claimId) {
        return ClaimResponse.from(review.startReview(claimId, staff.id()));
    }

    @Operation(summary = "장해지급률 판정 기록 추가",
            description = "심사 중인 장해 청구만. 고치지 않고 새 기록을 추가하며 가장 최근 기록이 현재 값. 50 이상이면 승인 시 점검 4 통과")
    @PostMapping("/{claimId}/assessments")
    public ResponseEntity<AssessmentResponse> addAssessment(@AuthenticationPrincipal LoginUser staff,
                                                            @PathVariable long claimId,
                                                            @Valid @RequestBody AssessmentRequest req) {
        ClaimAssessment a = review.addAssessment(claimId, staff.id(), req.disabilityRate(), req.basis(), req.reason());
        return ResponseEntity.created(URI.create("/api/admin/claims/" + claimId + "/assessments/" + a.getId()))
                .body(AssessmentResponse.from(a));
    }

    @Operation(summary = "청구 승인",
            description = "자동 점검 6가지를 다시 확인. 사망 → 지급건(PENDING) 생성 / 장해 → CLOSED + 계약 납입면제. "
                    + "점검 실패 409 CLAIM_CHECK_FAILED(details.failedChecks), 동시 처리 409 CONCURRENT_UPDATE")
    @PostMapping("/{claimId}/approve")
    public ClaimDecisionResponse approve(@AuthenticationPrincipal LoginUser staff, @PathVariable long claimId) {
        return ClaimDecisionResponse.approved(review.approve(claimId, staff.id()));
    }

    @Operation(summary = "청구 거절", description = "사유 필수 (7개 중 하나), 메모 선택. 거절 후 같은 종류로 다시 청구 가능")
    @PostMapping("/{claimId}/reject")
    public ClaimDecisionResponse reject(@AuthenticationPrincipal LoginUser staff, @PathVariable long claimId,
                                        @Valid @RequestBody RejectRequest req) {
        return ClaimDecisionResponse.rejected(review.reject(claimId, staff.id(), req.reason(), req.memo()));
    }
}
