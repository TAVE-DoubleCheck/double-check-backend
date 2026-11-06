package kr.tave.minicore.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * 상품 마스터 (V1). 적재(ingest)가 쓰고 도메인은 읽기만 한다.
 * created_at·updated_at은 적재 전용이라 매핑하지 않는다.
 */
@Entity
@Table(name = "product")
@Immutable
public class Product {

    @Id
    @Column(name = "product_code")
    private String productCode;

    @Column(name = "short_name")
    private String shortName;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "terms_code")
    private String termsCode;

    protected Product() {
    }

    public String getProductCode() {
        return productCode;
    }

    public String getShortName() {
        return shortName;
    }

    public String getFullName() {
        return fullName;
    }

    public String getTermsCode() {
        return termsCode;
    }
}
