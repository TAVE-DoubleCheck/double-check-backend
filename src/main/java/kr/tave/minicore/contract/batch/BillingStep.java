package kr.tave.minicore.contract.batch;

import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.domain.PremiumBill;
import kr.tave.minicore.contract.domain.PremiumSchedule;
import kr.tave.minicore.contract.repository.ContractRepository;
import kr.tave.minicore.contract.repository.PremiumBillRepository;
import kr.tave.minicore.daily.DailyWorkStage;
import kr.tave.minicore.daily.DailyWorkStep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 매일 작업 1단계: 청구서 발행.
 * 유지(ACTIVE) 계약마다 다음 회차의 납입기일(계약일 + n−1개월)이 오늘 이전이면 청구서를 만든다.
 * 납입완료·납입면제·실효·종료 계약은 발행하지 않는다. 마지막 회차 이후도 발행하지 않는다.
 * 같은 날 두 번 돌려도 "마지막 발행 회차 + 1"부터 보므로 중복 발행이 없다 (DB UNIQUE도 한 번 더 막음).
 */
@Component
@ConditionalOnProperty(name = "daily.contract-steps.enabled", havingValue = "true", matchIfMissing = true)
public class BillingStep implements DailyWorkStep {

    private final ContractRepository contracts;
    private final PremiumBillRepository bills;

    public BillingStep(ContractRepository contracts, PremiumBillRepository bills) {
        this.contracts = contracts;
        this.bills = bills;
    }

    @Override
    public DailyWorkStage stage() {
        return DailyWorkStage.BILLING;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(LocalDate businessDate) {
        for (Contract c : contracts.findByStatusOrderByIdAsc(ContractStatus.ACTIVE)) {
            int next = bills.findFirstByContractIdOrderByInstallmentNoDesc(c.getId())
                    .map(b -> b.getInstallmentNo() + 1)
                    .orElse(1);
            // 하루라도 빠졌을 때를 대비해 오늘까지 밀린 회차를 모두 발행한다
            while (next <= c.totalInstallments()) {
                LocalDate due = PremiumSchedule.dueDate(c.getContractDate(), next);
                if (due.isAfter(businessDate)) {
                    break;
                }
                bills.save(PremiumBill.issue(c, next, due, PremiumSchedule.graceEndDate(due)));
                next++;
            }
        }
    }
}
