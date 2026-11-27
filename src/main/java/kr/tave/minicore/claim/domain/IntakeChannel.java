package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 청구 접수 경로 (V5 claim.intake_channel).

/** 누가 청구를 접수했나 */
public enum IntakeChannel {
    CUSTOMER, // 고객이 직접 (장해)
    STAFF     // 직원이 유족 대신 (사망)
}
