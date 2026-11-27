package kr.tave.minicore.claim.repository;

// 새 파일 (#33): 청구 저장소.
// 변경 (#35): 승인 때 "자기 자신 제외" 중복 조회 추가.

import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimStatus;
import kr.tave.minicore.claim.domain.ClaimType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** 점검 6: 같은 계약·같은 종류에 거절 아닌 청구가 있는가 (DB uq_claim_active와 같은 조건) */
    boolean existsByContractIdAndTypeAndStatusNot(Long contractId, ClaimType type, ClaimStatus status);

    /** #35 승인 때 점검 6: 이 청구(id)를 뺀 나머지 중 거절 아닌 같은 종류 청구가 있는가 */
    boolean existsByContractIdAndTypeAndStatusNotAndIdNot(Long contractId, ClaimType type, ClaimStatus status,
                                                          Long id);
}
