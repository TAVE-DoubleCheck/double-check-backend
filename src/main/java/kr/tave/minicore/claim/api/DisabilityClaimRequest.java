package kr.tave.minicore.claim.api;

// 새 파일 (#33): 장해 청구 접수 요청 본문 (노션 명세 「장해 청구 접수」, API v0.4 7장).

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kr.tave.minicore.claim.domain.ClaimType;

import java.time.LocalDate;

/** 장해지급률은 고객이 넣지 않는다 (심사자가 판정) */
public record DisabilityClaimRequest(
        @NotNull Long contractId,
        @NotNull ClaimType type,                 // DISABILITY만 허용 (아래 검사)
        @NotNull LocalDate accidentDate,         // 사고일
        @NotBlank String accidentDescription) {  // 장해는 사고 설명 필수

    /** type이 DISABILITY가 아니면 400 (사망은 직원 대리 접수 주소로) */
    @AssertTrue(message = "고객 접수는 DISABILITY만 가능합니다 (사망은 직원 대리 접수)")
    public boolean isDisabilityType() {
        return type == null || type == ClaimType.DISABILITY; // null은 @NotNull이 따로 잡음
    }
}
