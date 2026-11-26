package kr.tave.minicore.contract.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 계약 상태 (V4 contract.status, 정리 v2 7.1).
 * 허용 전이는 이 enum의 표 한 곳에만 둔다. DB(ck_contract_status)는 값 목록만 검사한다.
 *
 * <pre>
 * PENDING ─첫 보험료 납부→ ACTIVE ─마지막 회차 납부→ PAID_UP
 * PENDING ─첫 보험료 미납→ VOID
 * ACTIVE  ─유예기간 끝까지 미납→ LAPSED          (약관 제28조①)
 * ACTIVE  ─장해 승인(BE C)→ PREMIUM_WAIVED       (약관 제4조①)
 * ACTIVE / PAID_UP / PREMIUM_WAIVED ─사망보험금 지급(BE C)→ TERMINATED
 * </pre>
 * LAPSED·TERMINATED·VOID는 1단계에서 끝 상태다 (실효 부활은 2단계 후보).
 */
public enum ContractStatus {
    PENDING,
    ACTIVE,
    PAID_UP,
    PREMIUM_WAIVED,
    LAPSED,
    TERMINATED,
    VOID;

    private static final Map<ContractStatus, Set<ContractStatus>> ALLOWED = new EnumMap<>(ContractStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(ACTIVE, VOID));
        ALLOWED.put(ACTIVE, EnumSet.of(PAID_UP, LAPSED, PREMIUM_WAIVED, TERMINATED));
        ALLOWED.put(PAID_UP, EnumSet.of(TERMINATED));
        ALLOWED.put(PREMIUM_WAIVED, EnumSet.of(TERMINATED));
        ALLOWED.put(LAPSED, EnumSet.noneOf(ContractStatus.class));
        ALLOWED.put(TERMINATED, EnumSet.noneOf(ContractStatus.class));
        ALLOWED.put(VOID, EnumSet.noneOf(ContractStatus.class));
    }

    /** 이 상태에서 next로 바꿀 수 있는가. 같은 상태로 바꾸는 것도 허용하지 않는다 (DB ck_contract_history_status와 같음). */
    public boolean canTransitionTo(ContractStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** 이 상태에서 갈 수 있는 다음 상태들 (오류 메시지·문서용) */
    public Set<ContractStatus> nextStatuses() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    /**
     * 보장 중인 상태인가. 청구 자동 점검 1번(사고 난 날 보장 중이었나)이 사고일 당시 상태로 판단할 때 쓴다.
     * 납입면제·납입완료도 보장은 계속된다. 청약(PENDING)은 첫 보험료 전이라 보장 전이다 (약관 제25조①).
     */
    public boolean isCovered() {
        return this == ACTIVE || this == PAID_UP || this == PREMIUM_WAIVED;
    }

    /** 더 이상 바뀌지 않는 상태인가 */
    public boolean isFinal() {
        return ALLOWED.get(this).isEmpty();
    }
}
