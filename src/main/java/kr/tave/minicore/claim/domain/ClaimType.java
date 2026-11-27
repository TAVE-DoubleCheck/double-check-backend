package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 청구 종류 (V5 ck_claim_type). 종류마다 접수 경로·청구인 관계가 정해져 있다 (회의 B-6).

/** 보험금 청구 종류 */
public enum ClaimType {
    DEATH,      // 사망: 직원이 유족 대신 접수 (STAFF), 승인되면 사망보험금 지급
    DISABILITY; // 장해: 고객 본인 접수 (CUSTOMER), 승인되면 돈 대신 납입면제

    /** 이 종류가 접수되는 경로. DB ck_claim_channel과 같은 규칙 */
    public IntakeChannel intakeChannel() {
        return this == DEATH ? IntakeChannel.STAFF : IntakeChannel.CUSTOMER;
    }
}
