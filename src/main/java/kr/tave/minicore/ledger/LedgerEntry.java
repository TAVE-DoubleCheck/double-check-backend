package kr.tave.minicore.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** V9 ledger의 읽기 전용 매핑. 쓰기는 원본에서 값을 가져오는 INSERT로만 수행한다. */
@Entity
@Table(name = "ledger")
@Immutable
public class LedgerEntry {
    @Id
    private Long id;

    @Column(name = "contract_id")
    private long contractId;

    private String type;
    private long amount;

    @Column(name = "occurred_on")
    private LocalDate occurredOn;

    @Column(name = "premium_bill_id")
    private Long premiumBillId;

    @Column(name = "payout_id")
    private Long payoutId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected LedgerEntry() { }

    public Long getId() { return id; }
    public long getContractId() { return contractId; }
    public String getType() { return type; }
    public long getAmount() { return amount; }
    public LocalDate getOccurredOn() { return occurredOn; }
    public Long getPremiumBillId() { return premiumBillId; }
    public Long getPayoutId() { return payoutId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
