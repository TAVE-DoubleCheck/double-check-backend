package kr.tave.minicore.product.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.tave.minicore.product.domain.Sex;
import kr.tave.minicore.product.query.ProductQueryService;
import kr.tave.minicore.product.quote.QuoteService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 상품 조회·보험료 계산 API. 비로그인 공개 (인증은 BE D 영역). */
@Tag(name = "상품", description = "상품 목록·상세 조회와 보험료 계산 (읽기 전용)")
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductQueryService query;
    private final QuoteService quote;

    public ProductController(ProductQueryService query, QuoteService quote) {
        this.query = query;
        this.quote = quote;
    }

    @Operation(summary = "상품 목록 (오늘 유효 버전이 있는 상품, subscribable로 1단계 가입 가능 구분)")
    @GetMapping
    public List<ProductSummaryResponse> list() {
        return query.list();
    }

    @Operation(summary = "상품 상세 (버전·가입나이 범위·선택 가능 조합·보장 문단)")
    @GetMapping("/{code}")
    public ProductDetailResponse detail(@PathVariable String code) {
        return query.detail(code);
    }

    @Operation(summary = "보험료 계산 (가입 미리보기. 날짜 YYYY-MM-DD, 금액 원 단위 정수)")
    @GetMapping("/{code}/quote")
    public QuoteResponse quote(
            @PathVariable String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthDate,
            @RequestParam Sex sex,
            @RequestParam int payTermYears,
            @RequestParam String insureTermCode,
            @RequestParam long amount) {
        return QuoteResponse.from(quote.quote(code, birthDate, sex, payTermYears, insureTermCode, amount));
    }
}
