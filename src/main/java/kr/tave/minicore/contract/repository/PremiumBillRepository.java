package kr.tave.minicore.contract.repository;

import kr.tave.minicore.contract.domain.BillStatus;
import kr.tave.minicore.contract.domain.PremiumBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PremiumBillRepository extends JpaRepository<PremiumBill, Long> {

    List<PremiumBill> findByContractIdOrderByInstallmentNoAsc(Long contractId);

    Optional<PremiumBill> findByContractIdAndInstallmentNo(Long contractId, short installmentNo);

    /** 납부 완료 회차 수 (목록·상세의 "3 / 120회") */
    long countByContractIdAndStatus(Long contractId, BillStatus status);

    /** 가장 최근에 발행한 회차 (BILLING이 다음 회차를 정할 때) */
    Optional<PremiumBill> findFirstByContractIdOrderByInstallmentNoDesc(Long contractId);

    /** 앞 회차 중 아직 안 낸 것이 있는지 (회차 순서대로만 납부) */
    boolean existsByContractIdAndInstallmentNoLessThanAndStatusNot(Long contractId, short installmentNo,
                                                                   BillStatus status);

    /** AUTO_DEBIT: 이 계약에서 오늘까지 납입기일이 된 미납 청구서 (회차 순) */
    List<PremiumBill> findByContractIdAndStatusInAndDueDateLessThanEqualOrderByInstallmentNoAsc(
            Long contractId, Collection<BillStatus> statuses, LocalDate date);

    /** LAPSE: 납입기일이 지난 발행 상태 청구서 → 미납 표시 */
    List<PremiumBill> findByStatusAndDueDateBeforeAndInstallmentNoGreaterThan(
            BillStatus status, LocalDate date, short installmentNo);

    /** LAPSE: 유예 마감이 지났는데 안 낸 청구서 */
    List<PremiumBill> findByStatusInAndGraceEndDateBeforeOrderByContractIdAscInstallmentNoAsc(
            Collection<BillStatus> statuses, LocalDate date);
}
