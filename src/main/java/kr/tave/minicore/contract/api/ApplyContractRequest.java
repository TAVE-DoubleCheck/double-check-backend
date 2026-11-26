package kr.tave.minicore.contract.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.tave.minicore.contract.domain.BeneficiaryRelation;

/**
 * 가입 신청. 생년월일·성별은 받지 않는다 (회원 정보 users.birth_date·sex를 쓴다).
 * 납입기간은 보험료 계산 API와 같이 payTermYears 또는 payTermPlan 중 정확히 하나.
 *
 * @param expectedVersionId      화면에서 본 보험료 계산 결과의 versionId
 * @param expectedMonthlyPremium 화면에서 본 월 보험료. 서버 재계산 값과 다르면 409 QUOTE_CHANGED
 */
public record ApplyContractRequest(
        @NotBlank String productCode,
        @NotBlank String insureTermCode,
        @NotNull @Positive Long amount,
        @Positive Integer payTermYears,
        String payTermPlan,
        @NotBlank @Size(max = 100) String beneficiaryName,
        @NotNull BeneficiaryRelation beneficiaryRelation,
        @NotNull Long expectedVersionId,
        @NotNull Long expectedMonthlyPremium
) {
}
