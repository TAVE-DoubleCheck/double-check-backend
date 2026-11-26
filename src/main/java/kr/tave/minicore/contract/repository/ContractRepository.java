package kr.tave.minicore.contract.repository;

import kr.tave.minicore.contract.domain.Contract;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, Long> {
}
