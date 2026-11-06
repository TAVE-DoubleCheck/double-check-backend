package kr.tave.minicore.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * 요율표 (V1). 월 보험료 = premium_won × 가입금액 ÷ unit_amount_won.
 */
@Entity
@Table(name = "premium_rate")
@Immutable
public class PremiumRate {

    @EmbeddedId
    private PremiumRateId id;

    /** 원본이 '전기납'이었는지 (pay_term_years는 치환된 값) */
    @Column(name = "is_full_term_pay")
    private boolean fullTermPay;

    /** 기준단위금액당 월 보험료 (원) */
    @Column(name = "premium_won")
    private long premiumWon;

    /** 기준단위금액 (원). 현재 데이터는 전부 1,000만 원 */
    @Column(name = "unit_amount_won")
    private long unitAmountWon;

    protected PremiumRate() {
    }

    public PremiumRateId getId() {
        return id;
    }

    public boolean isFullTermPay() {
        return fullTermPay;
    }

    public long getPremiumWon() {
        return premiumWon;
    }

    public long getUnitAmountWon() {
        return unitAmountWon;
    }
}
