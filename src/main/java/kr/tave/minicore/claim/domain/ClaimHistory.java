package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 청구 상태 변경 이력 1줄 (V5 claim_history). 추가만 하고 수정·삭제는 DB 트리거가 막는다.

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/** 청구 상태 이력 */
@Entity
@Immutable // JPA에서도 UPDATE를 만들지 않는다 (DB 트리거와 이중 방어)
@Table(name = "claim_history")
public class ClaimHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ClaimStatus fromStatus; // 접수 이력이면 null

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private ClaimStatus toStatus;

    @Column(name = "actor_id", nullable = false)
    private Long actorId; // 바꾼 로그인 계정

    @Column(name = "reason")
    private String reason;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn; // 업무 날짜

    protected ClaimHistory() {
    }

    public ClaimHistory(long claimId, ClaimStatus from, ClaimStatus to, long actorId, String reason,
                        LocalDate occurredOn) {
        this.claimId = claimId;
        this.fromStatus = from;
        this.toStatus = to;
        this.actorId = actorId;
        this.reason = reason;
        this.occurredOn = occurredOn;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public ClaimStatus getFromStatus() {
        return fromStatus;
    }

    public ClaimStatus getToStatus() {
        return toStatus;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getReason() {
        return reason;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }
}
