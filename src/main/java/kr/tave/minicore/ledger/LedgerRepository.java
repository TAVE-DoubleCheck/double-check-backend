package kr.tave.minicore.ledger;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** save/delete를 노출하지 않는다. 중복 시 UPDATE하지 않아 추가만 가능한 DB 규칙을 지킨다. */
public interface LedgerRepository extends Repository<LedgerEntry, Long> {
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO ledger (contract_id, type, amount, occurred_on, premium_bill_id)
            SELECT b.contract_id, 'PREMIUM_IN', b.amount, b.paid_on, b.id
            FROM premium_bill b WHERE b.id = :billId
            ON CONFLICT ON CONSTRAINT uq_ledger_premium_bill DO NOTHING
            """, nativeQuery = true)
    int insertPremiumIncome(@Param("billId") long billId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO ledger (contract_id, type, amount, occurred_on, payout_id)
            SELECT c.contract_id, 'CLAIM_OUT', p.amount, p.paid_on, p.id
            FROM payout p JOIN claim c ON c.id = p.claim_id WHERE p.id = :payoutId
            ON CONFLICT ON CONSTRAINT uq_ledger_payout DO NOTHING
            """, nativeQuery = true)
    int insertClaimPayout(@Param("payoutId") long payoutId);

    Optional<LedgerEntry> findByPremiumBillId(long premiumBillId);

    Optional<LedgerEntry> findByPayoutId(long payoutId);
}
