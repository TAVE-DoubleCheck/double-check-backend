package kr.tave.minicore.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.product.quote.PayTermPlan;
import kr.tave.minicore.product.quote.QuoteResult;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 계약서 1장 (V4 contract).
 * 가입 시점 스냅샷(상품 버전·월 보험료·보험나이·납입기간)은 QuoteResult를 그대로 복사하고 이후 바꾸지 않는다.
 *
 * 상태는 이 클래스의 메서드를 직접 부르지 말고 {@code ContractStatusService}를 거친다.
 * 서비스가 상태 변경과 이력(contract_history) 기록을 한 트랜잭션으로 묶는다.
 */
@Entity
@Table(name = "contract")
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** C-000123. DB 시퀀스 기본값으로 채번하고 저장 직후 다시 읽어 온다 */
    @Generated(event = EventType.INSERT)
    @Column(name = "contract_no", insertable = false, updatable = false)
    private String contractNo;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    // ── 가입 시점 스냅샷 (QuoteResult 그대로, 변경 금지) ──
    @Column(name = "version_id", nullable = false, updatable = false)
    private Long versionId;

    @Column(name = "sex", nullable = false, updatable = false)
    private Character sex;

    @Column(name = "insurance_age", nullable = false, updatable = false)
    private short insuranceAge;

    @Column(name = "insured_amount", nullable = false, updatable = false)
    private long insuredAmount;

    @Column(name = "monthly_premium", nullable = false, updatable = false)
    private long monthlyPremium;

    @Column(name = "pay_term_plan_type", nullable = false, updatable = false)
    private String payTermPlanType;

    @Column(name = "pay_term_plan_value", nullable = false, updatable = false)
    private short payTermPlanValue;

    @Column(name = "pay_term_years", nullable = false, updatable = false)
    private short payTermYears;

    @Column(name = "is_full_term_pay", nullable = false, updatable = false)
    private boolean fullTermPay;

    @Column(name = "insure_term_code", nullable = false, updatable = false)
    private String insureTermCode;

    @Column(name = "applied_on", nullable = false, updatable = false)
    private LocalDate appliedOn;

    // ── 첫 보험료 납부 때 확정 (약관 제25조①) ──
    @Column(name = "contract_date")
    private LocalDate contractDate;

    @Column(name = "billing_day")
    private Short billingDay;

    // ── 운영 ──
    @Column(name = "auto_debit", nullable = false)
    private boolean autoDebit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ContractStatus status;

    @Column(name = "beneficiary_name", nullable = false)
    private String beneficiaryName;

    @Column(name = "beneficiary_relation", nullable = false)
    private String beneficiaryRelation;

    /** 같은 계약을 두 곳에서 동시에 바꾸면 늦은 쪽이 실패한다 (낙관적 락) */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Contract() {
    }

    /**
     * 가입 신청 (청약). 서버에서 다시 계산한 QuoteResult를 그대로 복사한다 (docs/quote-contract.md).
     * 계약일·납입일은 첫 보험료를 낼 때 정해지므로 비워 둔다 (약관 제25조①).
     */
    public static Contract apply(long userId, QuoteResult quote, String beneficiaryName,
                                 BeneficiaryRelation beneficiaryRelation) {
        Contract c = new Contract();
        c.userId = userId;
        c.versionId = quote.versionId();
        c.sex = quote.sex().code();
        c.insuranceAge = (short) quote.insuranceAge();
        c.insuredAmount = quote.amount();
        c.monthlyPremium = quote.monthlyPremium();
        PayTermPlan plan = quote.payTermPlan();
        c.payTermPlanType = plan.type().name();
        c.payTermPlanValue = (short) (plan.type() == PayTermPlan.Type.FIXED_YEARS
                ? plan.years().intValue() : plan.toAge().intValue());
        c.payTermYears = (short) quote.payTermYears();
        c.fullTermPay = quote.isFullTermPay();
        c.insureTermCode = quote.insureTermCode();
        c.appliedOn = quote.businessDate();
        c.autoDebit = false;
        c.status = ContractStatus.PENDING;
        c.beneficiaryName = beneficiaryName;
        c.beneficiaryRelation = beneficiaryRelation.name();
        return c;
    }

    /**
     * 상태를 바꾼다. 허용되지 않은 전이면 409 INVALID_CONTRACT_STATUS.
     * 청약 → 유지는 계약일이 함께 정해져야 하므로 {@link #activate(LocalDate)}로만 한다.
     */
    public void changeStatus(ContractStatus next) {
        if (next == ContractStatus.ACTIVE) {
            throw new IllegalStateException("ACTIVE 전환은 activate(paidOn)으로 한다 (계약일 확정 필요)");
        }
        requireTransition(next);
        this.status = next;
    }

    /**
     * 첫 보험료를 낸 날 = 계약일 = 보장 시작일 (약관 제25조①). 매달 납입일은 계약일의 일자.
     * DB ck_contract_date_by_status가 ACTIVE인데 계약일이 없으면 거부하므로 셋을 함께 바꾼다.
     */
    public void activate(LocalDate paidOn) {
        requireTransition(ContractStatus.ACTIVE);
        this.status = ContractStatus.ACTIVE;
        this.contractDate = paidOn;
        this.billingDay = (short) paidOn.getDayOfMonth();
    }

    /**
     * 자동이체 켜기/끄기. 청약·유지 중에만 바꿀 수 있다 (납입완료·면제·실효·종료 후에는 낼 보험료가 없음).
     */
    public void changeAutoDebit(boolean enabled) {
        if (status != ContractStatus.PENDING && status != ContractStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT_STATUS,
                    "계약 " + id + "은(는) " + status + " 상태라 자동이체를 바꿀 수 없습니다.");
        }
        this.autoDebit = enabled;
    }

    /** 전체 납입 회차 = 납입년수 × 12 */
    public int totalInstallments() {
        return payTermYears * 12;
    }

    private void requireTransition(ContractStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT_STATUS,
                    "계약 " + id + "은(는) " + status + " 상태라 " + next + "(으)로 바꿀 수 없습니다. 가능한 다음 상태: "
                            + status.nextStatuses());
        }
    }

    public Long getId() {
        return id;
    }

    public String getContractNo() {
        return contractNo;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getVersionId() {
        return versionId;
    }

    public Character getSex() {
        return sex;
    }

    public short getInsuranceAge() {
        return insuranceAge;
    }

    public long getInsuredAmount() {
        return insuredAmount;
    }

    public long getMonthlyPremium() {
        return monthlyPremium;
    }

    public String getPayTermPlanType() {
        return payTermPlanType;
    }

    public short getPayTermPlanValue() {
        return payTermPlanValue;
    }

    public short getPayTermYears() {
        return payTermYears;
    }

    public boolean isFullTermPay() {
        return fullTermPay;
    }

    public String getInsureTermCode() {
        return insureTermCode;
    }

    public LocalDate getAppliedOn() {
        return appliedOn;
    }

    public LocalDate getContractDate() {
        return contractDate;
    }

    public Short getBillingDay() {
        return billingDay;
    }

    public boolean isAutoDebit() {
        return autoDebit;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public String getBeneficiaryRelation() {
        return beneficiaryRelation;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
