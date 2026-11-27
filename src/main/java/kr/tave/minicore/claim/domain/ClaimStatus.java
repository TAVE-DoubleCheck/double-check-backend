package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 청구 상태와 허용 전이 표 (V5 claim.status).
//   계약(ContractStatus, BE B)과 같은 방식: 허용 전이는 이 enum 한 곳에만 두고 DB는 값 목록만 검사한다.
//   이번 이슈는 접수(→ RECEIVED)만 쓰고, 나머지 전이는 심사·지급 이슈에서 쓴다.

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 청구 상태.
 * <pre>
 * RECEIVED ─심사 시작→ REVIEWING ─승인→ APPROVED ─(사망) 지급 실행→ PAID
 *                                │              └(장해) 납입면제 처리→ CLOSED
 *                                └─거절(사유 필수)→ REJECTED
 * </pre>
 */
public enum ClaimStatus {
    RECEIVED,  // 접수
    REVIEWING, // 심사 중
    APPROVED,  // 승인 (사망: 지급 대기 / 장해: 납입면제 처리 대기)
    REJECTED,  // 거절
    PAID,      // 지급 완료 (사망)
    CLOSED;    // 처리 완료 (장해)

    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = new EnumMap<>(ClaimStatus.class);

    static {
        ALLOWED.put(RECEIVED, EnumSet.of(REVIEWING));
        ALLOWED.put(REVIEWING, EnumSet.of(APPROVED, REJECTED));
        ALLOWED.put(APPROVED, EnumSet.of(PAID, CLOSED));
        ALLOWED.put(REJECTED, EnumSet.noneOf(ClaimStatus.class));
        ALLOWED.put(PAID, EnumSet.noneOf(ClaimStatus.class));
        ALLOWED.put(CLOSED, EnumSet.noneOf(ClaimStatus.class));
    }

    /** 이 상태에서 next로 바꿀 수 있는가 */
    public boolean canTransitionTo(ClaimStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** 이 상태에서 갈 수 있는 다음 상태들 (오류 메시지용) */
    public Set<ClaimStatus> nextStatuses() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }
}
