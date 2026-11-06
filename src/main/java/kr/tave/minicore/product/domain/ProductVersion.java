package kr.tave.minicore.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/**
 * 적용기간별 상품 버전 (V1). 계약은 이 id를 스냅샷으로 저장한다.
 * 같은 상품의 기간 겹침은 DB EXCLUDE 제약이 막으므로 "오늘 유효한 버전"은 최대 1개.
 */
@Entity
@Table(name = "product_version")
@Immutable
public class ProductVersion {

    @Id
    private Long id;

    @Column(name = "product_code")
    private String productCode;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** NULL = 판매 중 (원본 '99991231') */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "terms_url")
    private String termsUrl;

    protected ProductVersion() {
    }

    public Long getId() {
        return id;
    }

    public String getProductCode() {
        return productCode;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getTermsUrl() {
        return termsUrl;
    }
}
