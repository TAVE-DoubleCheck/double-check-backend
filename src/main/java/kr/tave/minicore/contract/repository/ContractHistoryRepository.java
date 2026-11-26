package kr.tave.minicore.contract.repository;

import kr.tave.minicore.contract.domain.ContractHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ContractHistoryRepository extends JpaRepository<ContractHistory, Long> {

    /** 계약 상세 화면의 상태 이력 (오래된 순) */
    List<ContractHistory> findByContractIdOrderByIdAsc(Long contractId);

    /** 그 날짜까지 일어난 마지막 변경 = 그날 끝 시점의 상태 (같은 날 여러 번이면 id가 큰 것) */
    Optional<ContractHistory> findFirstByContractIdAndOccurredOnLessThanEqualOrderByOccurredOnDescIdDesc(
            Long contractId, LocalDate date);
}
