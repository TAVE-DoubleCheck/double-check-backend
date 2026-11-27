package kr.tave.minicore.claim.service;

// 새 파일 (#35): 청구 심사 (직원). 심사 시작 → 장해지급률 판정 기록 → 승인 또는 거절.
//   승인 결과: 사망 = 지급건(PENDING) 생성 / 장해 = 청구 CLOSED + 계약 납입면제 (건아 ContractStatusService)
//   판정 규칙은 ClaimEligibility.checkApproval, 이 서비스는 사실을 모아 넘기고 결과를 저장한다.

import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimAssessment;
import kr.tave.minicore.claim.domain.ClaimCheck;
import kr.tave.minicore.claim.domain.ClaimEligibility;
import kr.tave.minicore.claim.domain.ClaimHistory;
import kr.tave.minicore.claim.domain.ClaimStatus;
import kr.tave.minicore.claim.domain.ClaimType;
import kr.tave.minicore.claim.domain.Payout;
import kr.tave.minicore.claim.domain.RejectReason;
import kr.tave.minicore.claim.repository.ClaimAssessmentRepository;
import kr.tave.minicore.claim.repository.ClaimHistoryRepository;
import kr.tave.minicore.claim.repository.ClaimRepository;
import kr.tave.minicore.claim.repository.PayoutRepository;
import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.repository.ContractRepository;
import kr.tave.minicore.contract.service.ContractStatusService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 청구 심사·승인·거절 */
@Service
public class ClaimReviewService {

    private final ClaimRepository claims;
    private final ClaimHistoryRepository histories;
    private final ClaimAssessmentRepository assessments;
    private final PayoutRepository payouts;
    private final ContractRepository contracts;
    private final ContractStatusService contractStatus;
    private final ClockService clock;

    public ClaimReviewService(ClaimRepository claims, ClaimHistoryRepository histories,
                              ClaimAssessmentRepository assessments, PayoutRepository payouts,
                              ContractRepository contracts, ContractStatusService contractStatus, ClockService clock) {
        this.claims = claims;
        this.histories = histories;
        this.assessments = assessments;
        this.payouts = payouts;
        this.contracts = contracts;
        this.contractStatus = contractStatus;
        this.clock = clock;
    }

    /** 승인 결과. 사망이면 payout, 장해면 contractStatus가 채워진다 */
    public record ApprovalResult(Claim claim, Payout payout, ContractStatus contractStatus) {
    }

    /** 심사 시작: RECEIVED → REVIEWING */
    @Transactional
    public Claim startReview(long claimId, long staffId) {
        Claim claim = load(claimId);
        claim.startReview();
        claims.flush(); // 동시에 누르면 늦은 쪽이 여기서 409 (@Version)
        record(claim, ClaimStatus.RECEIVED, staffId, "심사 시작");
        return claim;
    }

    /** 장해지급률 판정 기록 추가. 장해 청구·심사 중일 때만. 청구 상태는 바뀌지 않는다 */
    @Transactional
    public ClaimAssessment addAssessment(long claimId, long staffId, int disabilityRate, String basis, String reason) {
        Claim claim = load(claimId);
        if (claim.getType() != ClaimType.DISABILITY || claim.getStatus() != ClaimStatus.REVIEWING) {
            throw new ApiException(ErrorCode.INVALID_CLAIM_STATUS,
                    "판정 기록은 심사 중(REVIEWING)인 장해 청구에만 추가할 수 있습니다: " + claim.getType() + " " + claim.getStatus());
        }
        // 고치지 않고 새 줄 추가 → 가장 최근 줄이 현재 판정
        return assessments.save(new ClaimAssessment(claimId, disabilityRate, basis, reason, staffId, clock.today()));
    }

    /**
     * 승인 (한 트랜잭션): 점검 6가지 재확인 → 청구 APPROVED → 사망은 지급건 생성 / 장해는 CLOSED + 계약 납입면제.
     * 하나라도 실패하면 전부 취소된다 (계약 상태·지급건·이력 모두 그대로).
     */
    @Transactional
    public ApprovalResult approve(long claimId, long staffId) {
        Claim claim = load(claimId);
        requireReviewing(claim, "승인");
        Contract contract = contracts.findById(claim.getContractId()).orElseThrow();
        LocalDate today = clock.today();

        List<ClaimCheck> failed = ClaimEligibility.checkApproval(approvalFacts(claim, contract));
        if (!failed.isEmpty()) {
            throw checkFailed(failed);
        }

        claim.approve(today);
        // ★ 청구 변경을 먼저 DB에 반영: 두 직원이 동시에 승인하면 늦은 쪽이 여기서 @Version 충돌 → 409 CONCURRENT_UPDATE.
        //   지급건을 먼저 만들면 늦은 쪽이 payout UNIQUE 위반(500)으로 실패하므로 순서를 고정한다.
        claims.flush();
        record(claim, ClaimStatus.REVIEWING, staffId, "승인 (자동 점검 6가지 통과)");

        if (claim.getType() == ClaimType.DEATH) {
            // 사망보험금 = 승인 시점 가입금액 (단순화, API v0.4 9장). 지급 실행은 별도 API
            Payout payout = payouts.save(Payout.pending(claimId, contract.getInsuredAmount(), today));
            return new ApprovalResult(claim, payout, null);
        }

        // 장해: 돈 대신 남은 보험료 면제. 기준일 = 승인일 (이날 이후 청구서부터 면제, 회의 B-5)
        claim.close();
        record(claim, ClaimStatus.APPROVED, staffId, "납입면제 처리 (약관 제4조①)");
        Contract waived = contractStatus.waivePremium(contract.getId(), claim.getDecidedOn(), staffId, claimId);
        return new ApprovalResult(claim, null, waived.getStatus());
    }

    /** 거절: REVIEWING → REJECTED. 거절되면 같은 종류로 다시 청구할 수 있다 (부분 UNIQUE에서 빠짐) */
    @Transactional
    public Claim reject(long claimId, long staffId, RejectReason reason, String memo) {
        Claim claim = load(claimId);
        claim.reject(reason, memo, clock.today());
        claims.flush(); // 동시 승인·거절 충돌은 여기서 409
        record(claim, ClaimStatus.REVIEWING, staffId, "거절: " + reason + (memo == null ? "" : " / " + memo));
        return claim;
    }

    /** 승인 시점에 사실을 다시 모은다 (접수 때 값을 믿지 않음) */
    private ClaimEligibility.ApprovalFacts approvalFacts(Claim claim, Contract contract) {
        ClaimEligibility.IntakeFacts intake = new ClaimEligibility.IntakeFacts(
                claim.getType(),
                claim.getAccidentDate(),
                contractStatus.statusOn(contract.getId(), claim.getAccidentDate()).orElse(null), // 사고일 당시 상태
                contract.getContractDate(),
                contract.getPayTermYears(),
                claims.existsByContractIdAndTypeAndStatusNotAndIdNot( // 자기 자신은 빼고 중복 확인
                        contract.getId(), claim.getType(), ClaimStatus.REJECTED, claim.getId()));
        Integer latestRate = assessments.findFirstByClaimIdOrderByIdDesc(claim.getId())
                .map(ClaimAssessment::getDisabilityRate).orElse(null); // 최신 판정 (없으면 null → 점검 4 실패)
        return new ClaimEligibility.ApprovalFacts(intake, latestRate, beneficiaryMatches(claim, contract));
    }

    /** 점검 5: 청구인 이름·관계가 가입 때 정한 수익자와 같은가 (앞뒤 공백 무시) */
    private static boolean beneficiaryMatches(Claim claim, Contract contract) {
        return claim.getClaimantName().strip().equals(contract.getBeneficiaryName().strip())
                && claim.getClaimantRelation().name().equals(contract.getBeneficiaryRelation());
    }

    /** 승인 전에 상태부터 확인 → 이미 처리된 청구는 점검 실패가 아니라 상태 오류로 안내 */
    private static void requireReviewing(Claim claim, String action) {
        if (claim.getStatus() != ClaimStatus.REVIEWING) {
            throw new ApiException(ErrorCode.INVALID_CLAIM_STATUS,
                    action + "은(는) 심사 중(REVIEWING)인 청구만 가능합니다. 현재: " + claim.getStatus());
        }
    }

    private void record(Claim claim, ClaimStatus from, long staffId, String reason) {
        histories.save(new ClaimHistory(claim.getId(), from, claim.getStatus(), staffId, reason, clock.today()));
    }

    private Claim load(long claimId) {
        return claims.findById(claimId)
                .orElseThrow(() -> new ApiException(ErrorCode.CLAIM_NOT_FOUND, "청구 없음: " + claimId));
    }

    /** 409 CLAIM_CHECK_FAILED. 실패 항목 + 각 항목의 거절 사유 코드 (화면에서 거절로 바로 유도) */
    private static ApiException checkFailed(List<ClaimCheck> failed) {
        String message = "자동 점검 실패: " + failed.stream().map(ClaimCheck::message).collect(Collectors.joining(" / "));
        return new ApiException(ErrorCode.CLAIM_CHECK_FAILED, message, Map.of(
                "failedChecks", failed.stream().map(Enum::name).toList(),
                "suggestedRejectReasons", failed.stream().map(ClaimCheck::rejectReason).distinct().toList()));
    }
}
