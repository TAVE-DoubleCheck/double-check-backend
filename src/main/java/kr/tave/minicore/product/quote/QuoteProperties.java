package kr.tave.minicore.product.quote;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가입금액 한도 (기준단위금액 배수).
 * 단순화·확인 필요: 사업방법서의 실제 한도 미확인. 확인되면 설정값만 바꾼다.
 */
@ConfigurationProperties(prefix = "quote")
public record QuoteProperties(int amountMinUnits, int amountMaxUnits) {
}
