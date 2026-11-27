package kr.tave.minicore.claim.service;

// 새 파일 (#38): 사망보험금 지급 실행 (직원). 돈이 나가는 유일한 곳.
//   한 트랜잭션: 행 잠금 → PENDING 확인 → 지급건·청구 PAID → 장부 출금(BE D) → 계약 종료(BE B) → 청구 이력
//   하나라도 실패하면 전부 취소 (지급건·청구·장부·계약 모두 그대로).

import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimHistory;
import kr.tave.minicore.claim.domain.ClaimStatus;
import kr.tave.minicore.claim.domain.Payout;
import kr.tave.minicore.claim.domain.PayoutStatus;
import kr.tave.minicore.claim.repository.ClaimHistoryRepository;
import kr.tave.minicore.claim.repository.ClaimRepository;
import kr.tave.minicore.claim.repository.PayoutRepository;
import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.service.ContractStatusService;
import kr.tave.minicore.ledger.LedgerEntry;
import kr.tave.minicore.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 지급 실행 */
@Service
public class PayoutService {

    private final PayoutRepository payouts;
    private final ClaimRepository claims;
    private final ClaimHistoryRepository histories;
    private final LedgerService ledger;
    private final ContractStatusService contractStatus;
    private final ClockService clock;

    public PayoutService(PayoutRepository payouts, ClaimRepository claims, ClaimHistoryRepository histories,
                         LedgerService ledger, ContractStatusService contractStatus, ClockService clock) {
        this.payouts = payouts;
        this.claims = claims;
        this.histories = histories;
        this.ledger = ledger;
        this.contractStatus = contractStatus;
        this.clock = clock;
    }

    /** 지급 결과: 지급건·장부 줄·종료된 계약 */
    public record PayoutResult(Payout payout, LedgerEntry ledgerEntry, Contract contract) {
    }

    /**
     * 지급 실행.
     * ★ 중복 지급 방지: ① 행 잠금으로 동시 실행을 한 줄로 세우고 ② PENDING이 아니면 409
     *   (③ 장부 payout_id UNIQUE는 BE D의 마지막 방어선, ④ payout.claim_id UNIQUE는 승인 단계)
     */
    @Transactional
    public PayoutResult execute(long payoutId, long staffId) {
        // ① 잠금: 다른 직원의 지급 실행은 이 트랜잭션이 끝날 때까지 여기서 기다린다
        Payout payout = payouts.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYOUT_NOT_FOUND, "지급건 없음: " + payoutId));
        // ② 기다렸던 쪽은 앞 트랜잭션이 바꾼 PAID를 보고 여기서 멈춘다
        if (payout.getStatus() != PayoutStatus.PENDING) {
            throw new ApiException(ErrorCode.PAYOUT_ALREADY_PAID, "다른 직원이 이미 지급했습니다");
        }

        LocalDate today = clock.today();
        Claim claim = claims.findById(payout.getClaimId()).orElseThrow();

        // ③ 원본을 먼저 PAID로 (장부 V9 트리거가 "PAID + 금액·지급일 일치"를 검사하므로 순서 고정)
        payout.markPaid(today, staffId);
        claim.markPaid(); // APPROVED → PAID (아니면 409 INVALID_CLAIM_STATUS)

        // ④ 장부 출금: 지급 번호만 넘김. 금액·날짜·계약은 장부가 payout에서 직접 읽는다 (금액 출처 한 곳)
        //    recordClaimPayout은 JPA 변경을 먼저 flush하고 INSERT … SELECT 한다 (BE D, docs/ledger-schema.md)
        LedgerEntry entry = ledger.recordClaimPayout(payout.getId());

        // ⑤ 계약 종료 (사망보험금 지급 → TERMINATED). 날짜 = 지급일
        Contract contract = contractStatus.terminate(claim.getContractId(), today, staffId, payout.getId());

        histories.save(new ClaimHistory(claim.getId(), ClaimStatus.APPROVED, ClaimStatus.PAID, staffId,
                "사망보험금 지급 (지급 #" + payout.getId() + ", 장부 #" + entry.getId() + ")", today));
        return new PayoutResult(payout, entry, contract);
    }
}
