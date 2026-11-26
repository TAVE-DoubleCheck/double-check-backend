package kr.tave.minicore.contract.repository;

import kr.tave.minicore.contract.domain.PremiumBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PremiumBillRepository extends JpaRepository<PremiumBill, Long> {

    List<PremiumBill> findByContractIdOrderByInstallmentNoAsc(Long contractId);

    Optional<PremiumBill> findByContractIdAndInstallmentNo(Long contractId, short installmentNo);
}
