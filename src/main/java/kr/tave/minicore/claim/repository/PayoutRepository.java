package kr.tave.minicore.claim.repository;

// 새 파일 (#35): 지급건 저장소.
// 변경 (#38): 지급 실행용 행 잠금 조회 findByIdForUpdate 추가.

import jakarta.persistence.LockModeType;
import kr.tave.minicore.claim.domain.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    /** 청구 1건의 지급건 (최대 1개, claim_id UNIQUE) */
    Optional<Payout> findByClaimId(Long claimId);

    /**
     * ★ 지급 실행용 행 잠금 (SELECT … FOR UPDATE). 트랜잭션이 끝날 때까지 다른 지급 실행은 여기서 기다린다.
     *   기다린 쪽은 잠금이 풀린 뒤 PAID로 바뀐 값을 읽어 409 PAYOUT_ALREADY_PAID가 된다 (중복 지급 방지 2겹째).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payout p WHERE p.id = :id")
    Optional<Payout> findByIdForUpdate(@Param("id") long id);
}
