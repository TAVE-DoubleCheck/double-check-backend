package kr.tave.minicore.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** premium_rate 복합 PK (version_id, join_age, sex, pay_term_years, insure_term_code). */
@Embeddable
public class PremiumRateId implements Serializable {

    @Column(name = "version_id")
    private long versionId;

    @Column(name = "join_age")
    private short joinAge;

    /** DB CHAR(1): 'M' / 'W' */
    @Column(name = "sex")
    private Character sex;

    @Column(name = "pay_term_years")
    private short payTermYears;

    /** 원본 코드 그대로. '99'=종신, 그 외 = 만기(90·100세)까지 남은 연수 */
    @Column(name = "insure_term_code")
    private String insureTermCode;

    protected PremiumRateId() {
    }

    public PremiumRateId(long versionId, short joinAge, Character sex, short payTermYears, String insureTermCode) {
        this.versionId = versionId;
        this.joinAge = joinAge;
        this.sex = sex;
        this.payTermYears = payTermYears;
        this.insureTermCode = insureTermCode;
    }

    public long getVersionId() {
        return versionId;
    }

    public short getJoinAge() {
        return joinAge;
    }

    public Character getSex() {
        return sex;
    }

    public short getPayTermYears() {
        return payTermYears;
    }

    public String getInsureTermCode() {
        return insureTermCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PremiumRateId that)) {
            return false;
        }
        return versionId == that.versionId && joinAge == that.joinAge && payTermYears == that.payTermYears
                && Objects.equals(sex, that.sex) && Objects.equals(insureTermCode, that.insureTermCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionId, joinAge, sex, payTermYears, insureTermCode);
    }
}
