package kr.tave.minicore.ledger;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerService {
    private final LedgerRepository repository;

    public LedgerService(LedgerRepository repository) {
        this.repository = repository;
    }

    /** 호출자가 먼저 청구서를 PAID로 변경한다. 상태·금액·날짜 검증은 DB가 담당한다. */
    public LedgerEntry recordPremiumIncome(long billId) {
        repository.insertPremiumIncome(billId);
        // 충돌을 기다린 뒤 새 SELECT로 읽어 동시 요청에도 동일한 기존 기록을 반환한다.
        return repository.findByPremiumBillId(billId)
                .orElseThrow(() -> new EmptyResultDataAccessException("Premium bill not found: " + billId, 1));
    }

    /** 호출자가 먼저 지급건을 PAID로 변경한다. 원본의 claim에서 계약을 얻는다. */
    public LedgerEntry recordClaimPayout(long payoutId) {
        repository.insertClaimPayout(payoutId);
        return repository.findByPayoutId(payoutId)
                .orElseThrow(() -> new EmptyResultDataAccessException("Payout not found: " + payoutId, 1));
    }
}
