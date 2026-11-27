package kr.tave.minicore.claim.repository;

// 새 파일 (#35): 장해지급률 판정 기록 저장소 (추가만).

import kr.tave.minicore.claim.domain.ClaimAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClaimAssessmentRepository extends JpaRepository<ClaimAssessment, Long> {

    /** 최신 판정 = 가장 큰 id (V5 ix_claim_assessment_latest 인덱스 사용) */
    Optional<ClaimAssessment> findFirstByClaimIdOrderByIdDesc(Long claimId);
}
