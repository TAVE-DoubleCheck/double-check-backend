package kr.tave.minicore.claim.repository;

// 새 파일 (#33): 청구 저장소.

import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimStatus;
import kr.tave.minicore.claim.domain.ClaimType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** 점검 6: 같은 계약·같은 종류에 거절 아닌 청구가 있는가 (DB uq_claim_active와 같은 조건) */
    boolean existsByContractIdAndTypeAndStatusNot(Long contractId, ClaimType type, ClaimStatus status);
}
