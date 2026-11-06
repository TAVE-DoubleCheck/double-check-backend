package kr.tave.minicore.product.api;

import kr.tave.minicore.product.domain.InsureType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
        List<OptionDto> options,
        String insureTermNote,
        List<CoverageDto> coverages) {

    public record VersionDto(long versionId, LocalDate effectiveFrom, LocalDate effectiveTo, String termsUrl) {
    }

    public record AmountRange(long minWon, long maxWon) {
    }

    public record AgeRange(int min, int max) {
    }

    public record OptionDto(int payTermYears, boolean isFullTermPay, String insureTermCode) {
    }

    public record CoverageDto(int lineNo, String benefitName, String conditionText, boolean isNote) {
    }
}
