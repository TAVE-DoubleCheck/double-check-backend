package kr.tave.minicore.contract.domain;

/** 청구서 상태 (V4 premium_bill.status). 화면: 납부 대기 / 납입 완료 / 미납 */
public enum BillStatus {
    ISSUED,
    PAID,
    OVERDUE
}
