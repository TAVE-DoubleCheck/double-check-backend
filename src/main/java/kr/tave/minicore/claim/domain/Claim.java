package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 보험금 청구 1건 (V5 claim). 접수(생성).
// 변경 (#35): 심사 시작·승인·처리 완료·거절 메서드 추가. 지급 완료(PAID)는 지급 이슈에서 추가.

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/** 보험금 청구 */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private ClaimType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "accident_date", nullable = false, updatable = false)
    private LocalDate accidentDate; // 사고일 (사망은 사망일)

    @Column(name = "accident_description", updatable = false)
    private String accidentDescription; // 장해는 필수 (DB ck_claim_description)

    @Column(name = "claimant_name", nullable = false, updatable = false)
    private String claimantName;

    @Enumerated(EnumType.STRING)
    @Column(name = "claimant_relation", nullable = false, updatable = false)
    private ClaimantRelation claimantRelation;

    @Enumerated(EnumType.STRING)
    @Column(name = "intake_channel", nullable = false, updatable = false)
    private IntakeChannel intakeChannel;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy; // 접수한 로그인 계정

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn; // 접수일 (업무 날짜)

    @Column(name = "decided_on")
    private LocalDate decidedOn; // 승인·거절일 = 납입면제 기준일

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "reject_memo")
    private String rejectMemo;

    // ★ 낙관적 락: 직원 두 명이 같은 청구를 동시에 승인·거절하면 늦은 쪽이 409 (공통 처리 #31)
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Claim() {
    }

    /** 장해 청구 (고객 본인 접수). 청구인 = 본인 */
    public static Claim disability(long contractId, LocalDate accidentDate, String description,
                                   String customerName, long customerId, LocalDate today) {
        return create(contractId, ClaimType.DISABILITY, accidentDate, description,
                customerName, ClaimantRelation.SELF, customerId, today);
    }

    /** 사망 청구 (직원이 유족 대신 접수) */
    public static Claim death(long contractId, LocalDate deathDate, String claimantName,
                              ClaimantRelation relation, long staffId, LocalDate today) {
        if (relation == ClaimantRelation.SELF) { // 사망 청구인은 유족 (DB ck_claim_relation)
            throw new IllegalArgumentException("사망 청구인은 SELF일 수 없다");
        }
        return create(contractId, ClaimType.DEATH, deathDate, null, claimantName, relation, staffId, today);
    }

    private static Claim create(long contractId, ClaimType type, LocalDate accidentDate, String description,
                                String claimantName, ClaimantRelation relation, long createdBy, LocalDate today) {
        Claim c = new Claim();
        c.contractId = contractId;
        c.type = type;
        c.status = ClaimStatus.RECEIVED;          // 접수 상태로 시작
        c.accidentDate = accidentDate;
        c.accidentDescription = description;
        c.claimantName = claimantName;
        c.claimantRelation = relation;
        c.intakeChannel = type.intakeChannel();   // 종류가 정하면 경로도 정해짐
        c.createdBy = createdBy;
        c.receivedOn = today;
        return c;
    }

    // ── 상태 변경 (#35). 허용 전이는 ClaimStatus 표 한 곳, 어기면 409 INVALID_CLAIM_STATUS ──

    /** 심사 시작: RECEIVED → REVIEWING */
    public void startReview() {
        moveTo(ClaimStatus.REVIEWING);
    }

    /** 승인: REVIEWING → APPROVED. 승인일 = 납입면제 기준일 (회의 B-5) */
    public void approve(LocalDate today) {
        moveTo(ClaimStatus.APPROVED);
        this.decidedOn = today;
    }

    /** 장해 납입면제 처리 완료: APPROVED → CLOSED (돈은 나가지 않음) */
    public void close() {
        moveTo(ClaimStatus.CLOSED);
    }

    /** 거절: REVIEWING → REJECTED. 사유 필수 (DB ck_claim_reject) */
    public void reject(RejectReason reason, String memo, LocalDate today) {
        moveTo(ClaimStatus.REJECTED);
        this.rejectReason = reason.name();
        this.rejectMemo = memo;
        this.decidedOn = today;
    }

    private void moveTo(ClaimStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new ApiException(ErrorCode.INVALID_CLAIM_STATUS,
                    "청구 " + id + "은(는) " + status + " 상태라 " + next + "(으)로 바꿀 수 없습니다. 가능한 다음 상태: "
                            + status.nextStatuses());
        }
        this.status = next;
    }

    public Long getId() {
        return id;
    }

    public Long getContractId() {
        return contractId;
    }

    public ClaimType getType() {
        return type;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public LocalDate getAccidentDate() {
        return accidentDate;
    }

    public String getAccidentDescription() {
        return accidentDescription;
    }

    public String getClaimantName() {
        return claimantName;
    }

    public ClaimantRelation getClaimantRelation() {
        return claimantRelation;
    }

    public IntakeChannel getIntakeChannel() {
        return intakeChannel;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public LocalDate getDecidedOn() {
        return decidedOn;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public String getRejectMemo() {
        return rejectMemo;
    }
}
