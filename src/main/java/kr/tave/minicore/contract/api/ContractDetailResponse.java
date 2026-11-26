package kr.tave.minicore.contract.api;

import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.product.quote.PayTermPlan;

import java.time.LocalDate;

/**
 * 계약 상세. 가입 시점 스냅샷 + 지금 상태 + 다음에 낼 청구서.
 *
 * @param billingDay 매달 납입일 (계약일의 일자. 31이면 그달 말일). 청약 중이면 null
 * @param nextBill   아직 안 낸 가장 이른 청구서. 없으면 null
 */
public record ContractDetailResponse(
        long contractId,
        String contractNo,
        String productCode,
        String productName,
        long versionId,
        ContractStatus status,
        int insuranceAge,
        String sex,
        long insuredAmount,
        long monthlyPremium,
        PayTermPlan payTermPlan,
        int payTermYears,
        String insureTermCode,
        LocalDate appliedOn,
        LocalDate contractDate,
        Integer billingDay,
        boolean autoDebit,
        String beneficiaryName,
        String beneficiaryRelation,
        long paidInstallments,
        int totalInstallments,
        BillResponse nextBill
) {
}
