package kr.tave.minicore.contract.domain;

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

/**
 * 계약 상태 변경 이력 (V4 contract_history). 추가만 한다.
 * 수정·삭제는 DB 트리거(tg_contract_history_append_only)가 막고, 엔티티도 @Immutable로 UPDATE를 만들지 않는다.
 * 같은 날 여러 번 바뀌면 id 순서가 발생 순서다.
 */
@Entity
@Table(name = "contract_history")
@Immutable
public class ContractHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    /** 계약 생성 이력이면 null */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ContractStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private ContractStatus toStatus;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private Actor.Type actorType;

    /** 배치면 null */
    @Column(name = "actor_id")
    private Long actorId;

    /** 업무 날짜 (system_clock 기준) */
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    protected ContractHistory() {
    }

    public ContractHistory(Long contractId, ContractStatus fromStatus, ContractStatus toStatus,
                           String reason, Actor actor, LocalDate occurredOn) {
        this.contractId = contractId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actorType = actor.type();
        this.actorId = actor.userId();
        this.occurredOn = occurredOn;
    }

    public Long getId() {
        return id;
    }

    public Long getContractId() {
        return contractId;
    }

    public ContractStatus getFromStatus() {
        return fromStatus;
    }

    public ContractStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public Actor.Type getActorType() {
        return actorType;
    }

    public Long getActorId() {
        return actorId;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }
}
