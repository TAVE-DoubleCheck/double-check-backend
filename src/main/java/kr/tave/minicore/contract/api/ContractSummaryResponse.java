package kr.tave.minicore.contract.api;

import kr.tave.minicore.contract.domain.ContractStatus;

import java.time.LocalDate;

/**
 * 내 계약 목록의 1줄.
 *
 * @param contractDate     계약일 = 첫 보험료 낸 날. 청약(PENDING)·불성립(VOID)이면 null
 * @param paidInstallments 납입 완료 회차 수
 * @param totalInstallments 전체 납입 회차 = 납입년수 × 12
 */
public record ContractSummaryResponse(
        long contractId,
        String contractNo,
        String productCode,
        String productName,
        ContractStatus status,
        long insuredAmount,
        long monthlyPremium,
        LocalDate appliedOn,
        LocalDate contractDate,
        long paidInstallments,
        int totalInstallments
) {
}
