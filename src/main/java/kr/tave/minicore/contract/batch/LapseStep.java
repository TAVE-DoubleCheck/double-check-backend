package kr.tave.minicore.contract.batch;

import kr.tave.minicore.contract.domain.BillStatus;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.domain.PremiumBill;
import kr.tave.minicore.contract.repository.ContractRepository;
import kr.tave.minicore.contract.repository.PremiumBillRepository;
import kr.tave.minicore.contract.service.ContractStatusService;
import kr.tave.minicore.daily.DailyWorkStage;
import kr.tave.minicore.daily.DailyWorkStep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 매일 작업 3단계: 미납·실효·불성립.
 *  1. 납입기일이 지난 2회차 이후 청구서 → OVERDUE (화면: 미납. 유예 마감 전이라 아직 낼 수 있음)
 *  2. 유예 마감일이 지났는데 안 낸 청구서가 있는 계약
 *     - 유지(ACTIVE) → 실효(LAPSED). 실효일 = 오늘 = 유예 마감일 다음 날 (약관 제28조①)
 *     - 청약(PENDING)의 1회차 → 불성립(VOID). 1회차 대기 마감 = 신청일 + contract.first-premium-wait-days
 *  이미 실효·면제·종료 등인 계약은 건드리지 않는다.
 */
@Component
@ConditionalOnProperty(name = "daily.contract-steps.enabled", havingValue = "true", matchIfMissing = true)
public class LapseStep implements DailyWorkStep {

    private static final EnumSet<BillStatus> UNPAID = EnumSet.of(BillStatus.ISSUED, BillStatus.OVERDUE);

    private final PremiumBillRepository bills;
    private final ContractRepository contracts;
    private final ContractStatusService contractStatusService;

    public LapseStep(PremiumBillRepository bills, ContractRepository contracts,
                     ContractStatusService contractStatusService) {
        this.bills = bills;
        this.contracts = contracts;
        this.contractStatusService = contractStatusService;
    }

    @Override
    public DailyWorkStage stage() {
        return DailyWorkStage.LAPSE;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(LocalDate businessDate) {
        // 1. 미납 표시 (1회차는 계약 성립 전이라 "미납"으로 보이지 않게 제외)
        for (PremiumBill b : bills.findByStatusAndDueDateBeforeAndInstallmentNoGreaterThan(
                BillStatus.ISSUED, businessDate, (short) 1)) {
            b.markOverdue();
        }

        // 2. 유예 마감 경과 → 실효 / 불성립 (계약당 한 번, 가장 앞 회차 기준)
        Set<Long> done = new HashSet<>();
        for (PremiumBill b : bills.findByStatusInAndGraceEndDateBeforeOrderByContractIdAscInstallmentNoAsc(
                UNPAID, businessDate)) {
            if (!done.add(b.getContractId())) {
                continue;
            }
            Contract c = contracts.findById(b.getContractId()).orElseThrow();
            if (c.getStatus() == ContractStatus.ACTIVE) {
                contractStatusService.lapse(c.getId(), businessDate, b.getId());
            } else if (c.getStatus() == ContractStatus.PENDING && b.getInstallmentNo() == 1) {
                contractStatusService.voidContract(c.getId(), businessDate);
            }
        }
    }
}
