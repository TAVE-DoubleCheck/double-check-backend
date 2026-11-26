package kr.tave.minicore.contract.api;

import kr.tave.minicore.contract.domain.Actor;
import kr.tave.minicore.contract.domain.ContractHistory;
import kr.tave.minicore.contract.domain.ContractStatus;

import java.time.LocalDate;

/** 상태 이력 1줄. 직원 id(actor_id)는 고객 화면에 내보내지 않는다 */
public record ContractHistoryResponse(
        ContractStatus fromStatus,
        ContractStatus toStatus,
        String reason,
        Actor.Type actorType,
        LocalDate occurredOn
) {
    public static ContractHistoryResponse from(ContractHistory h) {
        return new ContractHistoryResponse(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getActorType(),
                h.getOccurredOn());
    }
}
