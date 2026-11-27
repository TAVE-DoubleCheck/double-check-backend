package kr.tave.minicore.claim.api;

// 새 파일 (#33): 청구 응답. 날짜 칸 이름은 계약 API(BE B, appliedOn·contractDate)와 같은 규칙:
//   일어난 날 ~On (receivedOn), 정해진 날 ~Date (accidentDate).

import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimStatus;
import kr.tave.minicore.claim.domain.ClaimType;
import kr.tave.minicore.claim.domain.ClaimantRelation;
import kr.tave.minicore.claim.domain.IntakeChannel;

import java.time.LocalDate;

public record ClaimResponse(
        long claimId,
        long contractId,
        ClaimType type,
        ClaimStatus status,
        LocalDate accidentDate,
        String claimantName,
        ClaimantRelation claimantRelation,
        IntakeChannel intakeChannel,
        LocalDate receivedOn) {

    /** 엔티티 → 응답 */
    public static ClaimResponse from(Claim c) {
        return new ClaimResponse(c.getId(), c.getContractId(), c.getType(), c.getStatus(), c.getAccidentDate(),
                c.getClaimantName(), c.getClaimantRelation(), c.getIntakeChannel(), c.getReceivedOn());
    }
}
