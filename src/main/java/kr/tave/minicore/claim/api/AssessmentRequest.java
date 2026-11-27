package kr.tave.minicore.claim.api;

// 새 파일 (#35): 장해지급률 판정 기록 요청 (노션 명세 「장해지급률 판정 기록 추가」, API v0.4 8장).

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssessmentRequest(
        @NotNull @Min(0) @Max(100) Integer disabilityRate, // 심사자가 합산한 최종 지급률(%)
        @NotBlank String basis,                            // 장해분류표(약관 별표3) 항목
        @NotBlank String reason) {                         // 판정 이유
}
