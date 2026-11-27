package kr.tave.minicore.claim.domain;

// 새 파일 (#35): 지급건 상태 (V5 ck_payout_status).

/** 지급건 상태 */
public enum PayoutStatus {
    PENDING, // 사망 청구 승인 직후, 지급 대기
    PAID     // 지급 실행 완료 (다음 이슈)
}
