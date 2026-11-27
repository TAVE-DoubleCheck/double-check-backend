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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 보험료 청구서 1장 (V4 premium_bill). 1회차는 가입 신청 때, 2회차부터는 매일 작업(BILLING)이 만든다.
 * 금액은 발행 시 contract.monthly_premium을 복사하고 다시 계산하지 않는다.
 * 같은 계약·같은 회차는 DB 유일 제약으로 한 장만 생긴다 (매일 작업을 두 번 돌려도 중복 발행 없음).
 */
@Entity
@Table(name = "premium_bill")
public class PremiumBill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private Long contractId;

    @Column(name = "installment_no", nullable = false, updatable = false)
    private short installmentNo;

    /** 납입기일 */
    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    /** 이날까지 안 내면 다음 날 실효(2회차~) 또는 불성립(1회차) */
    @Column(name = "grace_end_date", nullable = false, updatable = false)
    private LocalDate graceEndDate;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BillStatus status;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "paid_channel")
    private PaidChannel paidChannel;

    /** 같은 청구서를 동시에 두 번 납부하면 늦은 쪽이 실패한다 (낙관적 락) */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PremiumBill() {
    }

    private PremiumBill(Long contractId, short installmentNo, LocalDate dueDate, LocalDate graceEndDate, long amount) {
        this.contractId = contractId;
        this.installmentNo = installmentNo;
        this.dueDate = dueDate;
        this.graceEndDate = graceEndDate;
        this.amount = amount;
        this.status = BillStatus.ISSUED;
    }

    /** 새 청구서 (납부 대기) */
    public static PremiumBill issue(Contract contract, int installmentNo, LocalDate dueDate, LocalDate graceEndDate) {
        return new PremiumBill(contract.getId(), (short) installmentNo, dueDate, graceEndDate,
                contract.getMonthlyPremium());
    }

    /** 납부 완료. 같은 청구서를 동시에 두 번 내면 @Version 때문에 늦은 쪽이 실패한다 */
    public void markPaid(LocalDate on, PaidChannel channel) {
        this.status = BillStatus.PAID;
        this.paidOn = on;
        this.paidChannel = channel;
    }

    /** 납입기일이 지났는데 안 냄 (화면: 미납). 유예 마감 전이라 아직 낼 수 있다 */
    public void markOverdue() {
        if (status == BillStatus.ISSUED) {
            this.status = BillStatus.OVERDUE;
        }
    }

    public boolean isPaid() {
        return status == BillStatus.PAID;
    }

    public Long getId() {
        return id;
    }

    public Long getContractId() {
        return contractId;
    }

    public short getInstallmentNo() {
        return installmentNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getGraceEndDate() {
        return graceEndDate;
    }

    public long getAmount() {
        return amount;
    }

    public BillStatus getStatus() {
        return status;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public PaidChannel getPaidChannel() {
        return paidChannel;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
