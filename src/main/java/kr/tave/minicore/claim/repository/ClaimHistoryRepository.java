package kr.tave.minicore.claim.repository;

// 새 파일 (#33): 청구 상태 이력 저장소 (추가만).

import kr.tave.minicore.claim.domain.ClaimHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimHistoryRepository extends JpaRepository<ClaimHistory, Long> {

    /** 청구 1건의 이력 (오래된 순) */
    List<ClaimHistory> findByClaimIdOrderByIdAsc(Long claimId);
}
