package kr.tave.minicore.contract.repository;

import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContractRepository extends JpaRepository<Contract, Long> {

    /** 내 계약 목록 (최근 신청 순) */
    List<Contract> findByUserIdOrderByIdDesc(Long userId);

    /** 매일 작업 BILLING: 유지(ACTIVE) 계약 전체 */
    List<Contract> findByStatusOrderByIdAsc(ContractStatus status);

    /** 매일 작업 AUTO_DEBIT: 자동이체가 켜진 유지 계약 */
    List<Contract> findByStatusAndAutoDebitTrueOrderByIdAsc(ContractStatus status);
}
