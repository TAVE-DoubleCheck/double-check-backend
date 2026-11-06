package kr.tave.minicore.product.api;

import kr.tave.minicore.product.domain.InsureType;

import java.time.LocalDate;

/** GET /api/products 응답 항목 */
public record ProductSummaryResponse(
        String productCode,
        String shortName,
        String fullName,
        InsureType insureType,
        boolean subscribable,
        long versionId,
        LocalDate effectiveFrom) {
}
