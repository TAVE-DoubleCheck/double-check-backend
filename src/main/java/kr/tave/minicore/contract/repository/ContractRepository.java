package kr.tave.minicore.contract.repository;

import kr.tave.minicore.contract.domain.Contract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContractRepository extends JpaRepository<Contract, Long> {

    /** 내 계약 목록 (최근 신청 순) */
    List<Contract> findByUserIdOrderByIdDesc(Long userId);
}
