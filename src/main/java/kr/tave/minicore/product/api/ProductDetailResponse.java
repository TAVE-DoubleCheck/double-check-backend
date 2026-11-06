package kr.tave.minicore.product.api;

import kr.tave.minicore.product.domain.InsureType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** GET /api/products/{code} 응답 */
public record ProductDetailResponse(
        String productCode,
        String shortName,
        String fullName,
        String termsCode,
        InsureType insureType,
        boolean subscribable,
        VersionDto version,
        long unitAmountWon,
        AmountRange amountRange,
        /** 키: "M" / "W" */
        Map<String, AgeRange> joinAgeRange,
        /** 요율표에서 유도한 납입 플랜 (PayTermPlanAssembler). 상세 조합 56개 DISTINCT를 대체 */
        List<PayTermPlanDto> payTermPlans,
        List<String> insureTermCodes,
        String insureTermNote,
        List<CoverageDto> coverages) {

    public record VersionDto(long versionId, LocalDate effectiveFrom, LocalDate effectiveTo, String termsUrl) {
    }

    public record AmountRange(long minWon, long maxWon) {
    }

    public record AgeRange(int min, int max) {
    }

    /** type: FIXED_YEARS(years 사용) | TO_AGE(toAge 사용). joinAgeRange는 성별 키 "M"/"W" */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PayTermPlanDto(String type, Integer years, Integer toAge, Map<String, AgeRange> joinAgeRange) {
    }

    public record CoverageDto(int lineNo, String benefitName, String conditionText, boolean isNote) {
    }
}
