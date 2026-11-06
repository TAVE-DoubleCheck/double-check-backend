package kr.tave.minicore.product;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 1단계 가입 허용 상품 (설계 리뷰 안건 2: 종신 4종만).
 * 비종신은 플러스보험기간 산출방법서 확인 전이라 가입 불가.
 */
@ConfigurationProperties(prefix = "product")
public record ProductProperties(List<String> subscribableCodes) {

    public boolean isSubscribable(String productCode) {
        return subscribableCodes.contains(productCode);
    }
}
