package kr.tave.minicore.product.domain;

/** 보험기간 유형. 버전의 요율에 insure_term_code가 99만 있으면 WHOLE_LIFE, 아니면 TERM. */
public enum InsureType {
    WHOLE_LIFE, TERM
}
