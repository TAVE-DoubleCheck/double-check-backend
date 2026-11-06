package kr.tave.minicore.product.repository;

/** 버전·성별의 가입나이 범위. 요율이 한 행도 없으면 min/max가 null이다. */
public interface AgeRangeView {

    Integer getMin();

    Integer getMax();
}
