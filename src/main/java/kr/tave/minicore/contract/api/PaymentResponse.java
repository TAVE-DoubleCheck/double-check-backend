package kr.tave.minicore.contract.api;

import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.service.PremiumPaymentService.PaymentResult;

/**
 * 납부 결과.
 *
 * @param contractStatus 납부 후 계약 상태. 1회차면 ACTIVE(계약 성립), 마지막 회차면 PAID_UP
 * @param ledgerEntryId  장부 기록 번호 (입금 1줄)
 */
public record PaymentResponse(
        BillResponse bill,
        long contractId,
        ContractStatus contractStatus,
        long ledgerEntryId
) {
    public static PaymentResponse from(PaymentResult r) {
        return new PaymentResponse(BillResponse.from(r.bill()), r.bill().getContractId(), r.contractStatus(),
                r.ledgerEntry().getId());
    }
}
