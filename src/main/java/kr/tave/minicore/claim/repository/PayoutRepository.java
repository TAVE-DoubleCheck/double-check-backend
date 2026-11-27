package kr.tave.minicore.claim.repository;

// 새 파일 (#35): 지급건 저장소. 지급 실행용 행 잠금 조회는 지급 이슈에서 추가.

import kr.tave.minicore.claim.domain.Payout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    /** 청구 1건의 지급건 (최대 1개, claim_id UNIQUE) */
    Optional<Payout> findByClaimId(Long claimId);
}
