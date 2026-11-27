package kr.tave.minicore.claim.domain;

// 새 파일 (#35): 장해지급률 판정 기록 1줄 (V5 claim_assessment).
//   추가만 한다. 잘못 판정했으면 고치지 않고 새 기록을 추가하고, 가장 큰 id가 현재 값 (DB 트리거가 수정·삭제 차단).

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/** 장해지급률 판정 */
@Entity
@Immutable // JPA에서도 UPDATE를 만들지 않음 (DB 트리거와 이중 방어)
@Table(name = "claim_assessment")
public class ClaimAssessment {

    /** 납입면제 기준 지급률 (약관 제4조①, 50 포함) */
    public static final int WAIVER_THRESHOLD = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "disability_rate", nullable = false)
    private short disabilityRate; // 합산 장해지급률 0~100

    @Column(name = "basis", nullable = false)
    private String basis; // 약관 별표3 장해분류표 항목

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "assessed_by", nullable = false)
    private Long assessedBy; // 판정한 직원

    @Column(name = "assessed_on", nullable = false)
    private LocalDate assessedOn; // 업무 날짜

    protected ClaimAssessment() {
    }

    public ClaimAssessment(long claimId, int disabilityRate, String basis, String reason, long assessedBy,
                           LocalDate assessedOn) {
        this.claimId = claimId;
        this.disabilityRate = (short) disabilityRate;
        this.basis = basis;
        this.reason = reason;
        this.assessedBy = assessedBy;
        this.assessedOn = assessedOn;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public int getDisabilityRate() {
        return disabilityRate;
    }

    public String getBasis() {
        return basis;
    }

    public String getReason() {
        return reason;
    }

    public Long getAssessedBy() {
        return assessedBy;
    }

    public LocalDate getAssessedOn() {
        return assessedOn;
    }
}
