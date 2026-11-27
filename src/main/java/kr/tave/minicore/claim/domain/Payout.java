package kr.tave.minicore.claim.domain;

// 새 파일 (#35): 지급건 (V5 payout). 사망 청구 승인 때 PENDING으로 1건 생성.
// 변경 (#38): 지급 완료 markPaid 추가 (장부 기록은 PayoutService가 그 다음에 호출).
//   중복 지급 방지 1겹: claim_id UNIQUE (청구 1건당 지급건 최대 1개)

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/** 지급건 */
@Entity
@Table(name = "payout")
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount; // 지급액 = 승인 시점 가입금액 (장부는 이 값만 읽음)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PayoutStatus status;

    @Column(name = "approved_on", nullable = false, updatable = false)
    private LocalDate approvedOn; // 승인일

    @Column(name = "paid_on")
    private LocalDate paidOn; // 지급일 (PAID일 때만)

    @Column(name = "paid_by")
    private Long paidBy; // 지급한 직원 (PAID일 때만)

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payout() {
    }

    /** 사망 청구 승인 → 지급 대기 건 */
    public static Payout pending(long claimId, long amount, LocalDate approvedOn) {
        Payout p = new Payout();
        p.claimId = claimId;
        p.amount = amount;
        p.status = PayoutStatus.PENDING;
        p.approvedOn = approvedOn;
        return p;
    }

    /**
     * #38: 지급 완료 PENDING → PAID. 지급일·지급자를 함께 채운다 (DB ck_payout_paid).
     * 호출 전에 행 잠금 + PENDING 확인을 마친 상태여야 한다 (PayoutService).
     */
    public void markPaid(LocalDate today, long staffId) {
        if (status != PayoutStatus.PENDING) { // 서비스에서 먼저 막지만 엔티티도 한 번 더 지킴
            throw new IllegalStateException("PENDING이 아닌 지급건은 지급할 수 없다: " + id + " " + status);
        }
        this.status = PayoutStatus.PAID;
        this.paidOn = today;
        this.paidBy = staffId;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public long getAmount() {
        return amount;
    }

    public PayoutStatus getStatus() {
        return status;
    }

    public LocalDate getApprovedOn() {
        return approvedOn;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public Long getPaidBy() {
        return paidBy;
    }
}
