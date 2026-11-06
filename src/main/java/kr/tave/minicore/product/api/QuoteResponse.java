package kr.tave.minicore.product.api;

import kr.tave.minicore.product.domain.Sex;
import kr.tave.minicore.product.quote.QuoteResult;

import java.time.LocalDate;
import java.util.List;

/** GET /api/products/{code}/quote 응답. QuoteResult와 1:1 (BE B는 QuoteResult를 직접 쓴다). */
public record QuoteResponse(
        String productCode,
        long versionId,
        LocalDate effectiveFrom,
        int insuranceAge,
        Sex sex,
        int payTermYears,
        boolean isFullTermPay,
        String insureTermCode,
        long amount,
        long unitAmountWon,
        long premiumWonPerUnit,
        long monthlyPremium,
        LocalDate businessDate,
        List<String> assumptions) {

    public static QuoteResponse from(QuoteResult r) {
        return new QuoteResponse(r.productCode(), r.versionId(), r.effectiveFrom(),
                r.insuranceAge(), r.sex(), r.payTermYears(), r.isFullTermPay(), r.insureTermCode(),
                r.amount(), r.unitAmountWon(), r.premiumWonPerUnit(), r.monthlyPremium(),
                r.businessDate(), r.assumptions());
    }
}
