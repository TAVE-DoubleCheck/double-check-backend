package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 청구인과 피보험자의 관계 (V5 ck_claim_relation).
//   사망 청구의 값 4개는 계약 수익자 관계(BeneficiaryRelation, V4)와 같은 이름 → 심사 때 점검 5(수익자 일치)에서 그대로 비교.

import kr.tave.minicore.contract.domain.BeneficiaryRelation;

/** 청구인 관계 */
public enum ClaimantRelation {
    SELF,    // 본인 (장해 청구만)
    SPOUSE,  // 배우자
    CHILD,   // 자녀
    PARENT,  // 부모
    SIBLING; // 형제자매

    /** 사망 청구인 관계 = 계약 수익자 관계 코드 그대로 (이름이 같아서 변환만 한다) */
    public static ClaimantRelation from(BeneficiaryRelation relation) {
        return ClaimantRelation.valueOf(relation.name());
    }
}
