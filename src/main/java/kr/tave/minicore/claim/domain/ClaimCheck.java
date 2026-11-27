package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 청구 자동 점검 6가지의 이름표 (노션 API 명세 「자동 점검 6가지」의 check 이름 그대로).
//   실패하면 거절 사유 코드(V5 ck_claim_reject_reason)와 1:1로 이어진다 → 심사 이슈에서 거절 사유 자동 채움에 쓴다.

/** 자동 점검 항목 */
public enum ClaimCheck {
    COVERED_ON_ACCIDENT_DATE("NOT_COVERED", "사고일에 보장 중인 계약이 아닙니다"),          // 1. 사고일 당시 상태가 보장 중
    AFTER_CONTRACT_START("BEFORE_CONTRACT", "계약 성립(첫 보험료 납부) 전에 난 사고입니다"),  // 2. 계약일 이후 사고
    WITHIN_PAY_TERM("AFTER_PAY_TERM", "보험료 납입기간이 끝난 뒤 난 장해입니다"),           // 3. 납입기간 중 장해 (납입면제 대상)
    DISABILITY_RATE_50("DISABILITY_RATE_UNDER_50", "장해지급률이 50% 미만입니다"),        // 4. 심사 때만
    BENEFICIARY_MATCH("BENEFICIARY_MISMATCH", "청구인이 계약 수익자와 다릅니다"),           // 5. 심사 때만
    NOT_DUPLICATE("DUPLICATE", "진행 중이거나 승인된 같은 종류의 청구가 있습니다");         // 6. 거절된 건은 제외

    private final String rejectReason; // 실패 시 거절 사유 코드
    private final String message;      // 실패 시 안내 문장 (개발자용, 화면 문구는 FE)

    ClaimCheck(String rejectReason, String message) {
        this.rejectReason = rejectReason;
        this.message = message;
    }

    /** 이 점검이 실패했을 때 쓰는 거절 사유 코드 (claim.reject_reason) */
    public String rejectReason() {
        return rejectReason;
    }

    /** 실패 안내 문장 */
    public String message() {
        return message;
    }
}
