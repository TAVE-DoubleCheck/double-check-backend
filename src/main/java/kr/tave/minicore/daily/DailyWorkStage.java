package kr.tave.minicore.daily;

/** 하루 업무의 실행 순서. 빈 등록 순서에 의존하지 않는다. */
public enum DailyWorkStage {
    BILLING,
    AUTO_DEBIT,
    LAPSE,
    MATURITY,
    RECONCILIATION
}
