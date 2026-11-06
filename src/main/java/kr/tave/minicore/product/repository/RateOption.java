package kr.tave.minicore.product.repository;

/** 가입 화면용 선택 가능 조합 (버전별 DISTINCT). */
public record RateOption(short payTermYears, boolean isFullTermPay, String insureTermCode) {
}
