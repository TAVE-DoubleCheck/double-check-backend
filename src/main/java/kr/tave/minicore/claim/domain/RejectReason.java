package kr.tave.minicore.claim.domain;

// 새 파일 (#35): 청구 거절 사유 (V5 ck_claim_reject_reason 7개와 같은 값).
//   6개는 자동 점검 실패와 1:1 (ClaimCheck.rejectReason), EXEMPTION_ARTICLE_5만 심사자 판단.

/** 거절 사유 */
public enum RejectReason {
    NOT_COVERED,              // 사고 당시 보장 중 아님 (점검 1)
    BEFORE_CONTRACT,          // 계약 성립 전 사고 (점검 2)
    AFTER_PAY_TERM,           // 납입기간 이후 장해 (점검 3)
    DISABILITY_RATE_UNDER_50, // 장해지급률 50% 미만 (점검 4)
    BENEFICIARY_MISMATCH,     // 청구인 ≠ 수익자 (점검 5)
    DUPLICATE,                // 중복 청구 (점검 6)
    EXEMPTION_ARTICLE_5       // 약관 제5조 면책 (고의 사고 등, 심사자 판단)
}
