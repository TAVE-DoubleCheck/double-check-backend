package kr.tave.minicore.product.repository;

/** 납입 플랜 유도용: 버전의 (성별, 가입나이, 납입년수) DISTINCT 튜플. */
public interface PayAgeView {

    Character getSex();

    Short getJoinAge();

    Short getPayTermYears();
}
