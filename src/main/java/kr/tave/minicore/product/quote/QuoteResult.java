package kr.tave.minicore.product.quote;

import kr.tave.minicore.product.domain.Sex;

import java.time.LocalDate;
import java.util.List;

/**
 * 보험료 계산 결과. BE B(계약)는 가입 시 이 값(특히 versionId·monthlyPremium·insuranceAge)을
 * 그대로 계약에 저장하고 다시 계산하지 않는다 → 요율이 바뀌어도 기존 계약 불변.
 *
 * @param versionId        가입 시점에 고정할 상품 버전 id
 * @param insuranceAge     보험나이 (약관 제23조②, 계약일 = businessDate)
 * @param payTermYears     실제 적용된 납입년수 (TO_AGE 플랜이면 toAge − 보험나이로 변환된 값)
 * @param payTermPlan      선택한 납입 플랜. BE B는 payTermYears와 함께 둘 다 계약에 저장
 * @param premiumWonPerUnit 기준단위금액당 월 보험료 (요율표 원본)
 * @param monthlyPremium   월 보험료 = premiumWonPerUnit × (amount ÷ unitAmountWon)
 * @param businessDate     계산에 쓴 업무 날짜 (system_clock)
 * @param assumptions      FE가 작은 글씨로 표시할 단순화·가정 목록
 */
public record QuoteResult(
        String productCode,
        long versionId,
        LocalDate effectiveFrom,
        int insuranceAge,
        Sex sex,
        int payTermYears,
        PayTermPlan payTermPlan,
        boolean isFullTermPay,
        String insureTermCode,
        long amount,
        long unitAmountWon,
        long premiumWonPerUnit,
        long monthlyPremium,
        LocalDate businessDate,
        List<String> assumptions) {
}
