package kr.tave.minicore.claim.api;

// 새 파일 (#35): 승인·거절 결과 응답. 경우에 따라 채워지는 칸이 달라 빈 칸은 JSON에서 뺀다.
//   사망 승인: payoutId·payoutAmount / 장해 승인: contractStatus / 거절: rejectReason·rejectMemo

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimStatus;
import kr.tave.minicore.claim.domain.ClaimType;
import kr.tave.minicore.claim.service.ClaimReviewService.ApprovalResult;
import kr.tave.minicore.contract.domain.ContractStatus;

import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimDecisionResponse(
        long claimId,
        ClaimType type,
        ClaimStatus status,
        LocalDate decidedOn,          // 승인·거절일 (장해는 납입면제 기준일)
        Long payoutId,                // 사망 승인
        Long payoutAmount,            // 사망 승인: 지급 예정액 = 가입금액
        ContractStatus contractStatus, // 장해 승인: PREMIUM_WAIVED
        String rejectReason,          // 거절
        String rejectMemo) {

    /** 승인 결과 → 응답 */
    public static ClaimDecisionResponse approved(ApprovalResult r) {
        Claim c = r.claim();
        return new ClaimDecisionResponse(c.getId(), c.getType(), c.getStatus(), c.getDecidedOn(),
                r.payout() == null ? null : r.payout().getId(),
                r.payout() == null ? null : r.payout().getAmount(),
                r.contractStatus(), null, null);
    }

    /** 거절 결과 → 응답 */
    public static ClaimDecisionResponse rejected(Claim c) {
        return new ClaimDecisionResponse(c.getId(), c.getType(), c.getStatus(), c.getDecidedOn(),
                null, null, null, c.getRejectReason(), c.getRejectMemo());
    }
}
