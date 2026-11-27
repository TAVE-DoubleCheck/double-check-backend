package kr.tave.minicore.claim.api;

// 새 파일 (#33): 사망 청구 대리 접수 요청 본문 (노션 명세 「사망 청구 대리 접수」, API v0.4 8장).
//   관계 코드는 계약 수익자 코드(V4 BeneficiaryRelation)와 같은 값을 받는다 → 심사 때 그대로 비교.

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kr.tave.minicore.contract.domain.BeneficiaryRelation;

import java.time.LocalDate;

public record DeathClaimRequest(
        @NotNull Long contractId,
        @NotNull LocalDate deathDate,                 // 사망일 = 사고일 (claim.accident_date)
        @NotBlank String claimantName,                // 청구인(유족) 이름
        @NotNull BeneficiaryRelation claimantRelation) { // SPOUSE·CHILD·PARENT·SIBLING
}
