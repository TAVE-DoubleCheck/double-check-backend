package kr.tave.minicore.claim.api;

// 새 파일 (#38): 지급 실행 응답 (노션 명세 「지급 실행」 [확정] 4칸 + claimId·contractStatus).

import kr.tave.minicore.claim.service.PayoutService.PayoutResult;
import kr.tave.minicore.contract.domain.ContractStatus;

import java.time.LocalDate;

public record PayoutExecuteResponse(
        long payoutId,
        long claimId,
        long amount,                  // 지급액 = 가입금액 (단순화)
        LocalDate paidOn,             // 업무 날짜
        long ledgerEntryId,           // 장부 출금 줄 id
        ContractStatus contractStatus) { // TERMINATED

    /** 결과 → 응답 */
    public static PayoutExecuteResponse from(PayoutResult r) {
        return new PayoutExecuteResponse(r.payout().getId(), r.payout().getClaimId(), r.payout().getAmount(),
                r.payout().getPaidOn(), r.ledgerEntry().getId(), r.contract().getStatus());
    }
}
