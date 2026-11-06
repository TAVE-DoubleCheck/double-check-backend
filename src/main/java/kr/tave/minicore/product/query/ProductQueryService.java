package kr.tave.minicore.product.query;

import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.product.ProductProperties;
import kr.tave.minicore.product.api.ProductDetailResponse;
import kr.tave.minicore.product.api.ProductSummaryResponse;
import kr.tave.minicore.product.domain.InsureType;
import kr.tave.minicore.product.domain.Product;
import kr.tave.minicore.product.domain.ProductVersion;
import kr.tave.minicore.product.quote.QuoteProperties;
import kr.tave.minicore.product.repository.AgeRangeView;
import kr.tave.minicore.product.repository.CoverageRepository;
import kr.tave.minicore.product.repository.PremiumRateRepository;
import kr.tave.minicore.product.repository.ProductRepository;
import kr.tave.minicore.product.repository.ProductVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 상품 목록·상세 조회 (읽기 전용). */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    static final String INSURE_TERM_NOTE = "코드는 가입나이 기준 만기까지 남은 연수. 99=종신";

    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final PremiumRateRepository rates;
    private final CoverageRepository coverages;
    private final ProductProperties productProps;
    private final QuoteProperties quoteProps;
    private final ClockService clock;

    public ProductQueryService(ProductRepository products, ProductVersionRepository versions,
                               PremiumRateRepository rates, CoverageRepository coverages,
                               ProductProperties productProps, QuoteProperties quoteProps, ClockService clock) {
        this.products = products;
        this.versions = versions;
        this.rates = rates;
        this.coverages = coverages;
        this.productProps = productProps;
        this.quoteProps = quoteProps;
        this.clock = clock;
    }

    /** 오늘 유효한 버전이 있는 상품만 반환한다 (현재 데이터는 8종 전부). */
    public List<ProductSummaryResponse> list() {
        Map<String, ProductVersion> effective = versions.findAllEffective(clock.today()).stream()
                .collect(Collectors.toMap(ProductVersion::getProductCode, Function.identity()));

        return products.findAllByOrderByProductCode().stream()
                .filter(p -> effective.containsKey(p.getProductCode()))
                .map(p -> {
                    ProductVersion v = effective.get(p.getProductCode());
                    return new ProductSummaryResponse(p.getProductCode(), p.getShortName(), p.getFullName(),
                            insureTypeOf(v.getId()), productProps.isSubscribable(p.getProductCode()),
                            v.getId(), v.getEffectiveFrom());
                })
                .toList();
    }

    public ProductDetailResponse detail(String productCode) {
        Product product = products.findById(productCode)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품코드 없음: " + productCode));

        LocalDate today = clock.today();
        ProductVersion version = versions.findEffective(productCode, today)
                .orElseThrow(() -> new ApiException(ErrorCode.NO_EFFECTIVE_VERSION,
                        "오늘(" + today + ") 유효한 상품 버전이 없습니다: " + productCode));
        long versionId = version.getId();

        long unitAmountWon = singleUnitAmount(versionId);

        Map<String, ProductDetailResponse.AgeRange> joinAgeRange = new LinkedHashMap<>();
        for (char sex : new char[]{'M', 'W'}) {
            AgeRangeView range = rates.findJoinAgeRange(versionId, sex);
            if (range.getMin() != null) {
                joinAgeRange.put(String.valueOf(sex),
                        new ProductDetailResponse.AgeRange(range.getMin(), range.getMax()));
            }
        }

        return new ProductDetailResponse(
                product.getProductCode(), product.getShortName(), product.getFullName(), product.getTermsCode(),
                insureTypeOf(versionId), productProps.isSubscribable(productCode),
                new ProductDetailResponse.VersionDto(versionId, version.getEffectiveFrom(),
                        version.getEffectiveTo(), version.getTermsUrl()),
                unitAmountWon,
                new ProductDetailResponse.AmountRange(
                        unitAmountWon * quoteProps.amountMinUnits(), unitAmountWon * quoteProps.amountMaxUnits()),
                joinAgeRange,
                rates.findOptions(versionId).stream()
                        .map(o -> new ProductDetailResponse.OptionDto(
                                o.payTermYears(), o.isFullTermPay(), o.insureTermCode()))
                        .toList(),
                INSURE_TERM_NOTE,
                coverages.findByVersionIdOrderBySourceLineNo(versionId).stream()
                        .map(c -> new ProductDetailResponse.CoverageDto(
                                c.getSourceLineNo(), c.getBenefitName(), c.getConditionText(), c.isNote()))
                        .toList());
    }

    private InsureType insureTypeOf(long versionId) {
        return rates.existsById_VersionIdAndId_InsureTermCodeNot(versionId, "99")
                ? InsureType.TERM : InsureType.WHOLE_LIFE;
    }

    private long singleUnitAmount(long versionId) {
        List<Long> units = rates.findDistinctUnitAmounts(versionId);
        if (units.size() != 1) {
            throw new IllegalStateException("기준단위금액이 1종류가 아님 (version " + versionId + "): " + units);
        }
        return units.get(0);
    }
}
