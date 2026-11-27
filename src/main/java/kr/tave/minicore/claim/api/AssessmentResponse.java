package kr.tave.minicore.claim.api;

// 새 파일 (#35): 장해지급률 판정 기록 응답.

import kr.tave.minicore.claim.domain.ClaimAssessment;

import java.time.LocalDate;

public record AssessmentResponse(long assessmentId, long claimId, int disabilityRate, String basis, String reason,
                                 long assessedBy, LocalDate assessedOn) {

    /** 엔티티 → 응답 */
    public static AssessmentResponse from(ClaimAssessment a) {
        return new AssessmentResponse(a.getId(), a.getClaimId(), a.getDisabilityRate(), a.getBasis(), a.getReason(),
                a.getAssessedBy(), a.getAssessedOn());
    }
}
