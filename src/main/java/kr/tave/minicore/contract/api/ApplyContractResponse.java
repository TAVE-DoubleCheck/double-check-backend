package kr.tave.minicore.contract.api;

import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.domain.PremiumBill;
import kr.tave.minicore.product.quote.PayTermPlan;

import java.time.LocalDate;

/** 가입 신청 결과. 청약(PENDING) 계약 + 1회차 청구서 */
public record ApplyContractResponse(
        long contractId,
        String contractNo,
        ContractStatus status,
        String productCode,
        long versionId,
        int insuranceAge,
        long insuredAmount,
        long monthlyPremium,
        PayTermPlan payTermPlan,
        int payTermYears,
        LocalDate appliedOn,
        FirstBill firstBill
) {
    /**
     * @param payBy 이날까지 내면 계약 성립(낸 날 = 계약일 = 보장 시작일). 지나면 불성립
     */
    public record FirstBill(long billId, int installmentNo, long amount, LocalDate dueDate, LocalDate payBy) {
    }

    public static ApplyContractResponse of(Contract c, String productCode, PayTermPlan plan, PremiumBill bill) {
        return new ApplyContractResponse(c.getId(), c.getContractNo(), c.getStatus(), productCode, c.getVersionId(),
                c.getInsuranceAge(), c.getInsuredAmount(), c.getMonthlyPremium(), plan, c.getPayTermYears(),
                c.getAppliedOn(),
                new FirstBill(bill.getId(), bill.getInstallmentNo(), bill.getAmount(), bill.getDueDate(),
                        bill.getGraceEndDate()));
    }
}
