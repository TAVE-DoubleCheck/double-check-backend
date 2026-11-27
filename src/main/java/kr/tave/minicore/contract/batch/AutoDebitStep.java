package kr.tave.minicore.contract.batch;

import kr.tave.minicore.contract.domain.Actor;
import kr.tave.minicore.contract.domain.BillStatus;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.domain.PaidChannel;
import kr.tave.minicore.contract.domain.PremiumBill;
import kr.tave.minicore.contract.repository.ContractRepository;
import kr.tave.minicore.contract.repository.PremiumBillRepository;
import kr.tave.minicore.contract.service.PremiumPaymentService;
import kr.tave.minicore.daily.DailyWorkStage;
import kr.tave.minicore.daily.DailyWorkStep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumSet;

/**
 * 매일 작업 2단계: 자동이체.
 * 자동이체가 켜진 유지 계약에서 납입기일이 된(또는 지났지만 유예 마감 전인) 미납 청구서를 회차 순으로 납부한다.
 * 납부는 화면 납부와 같은 PremiumPaymentService를 쓴다 → 청구서 PAID + 장부 입금 + (마지막 회차면) 납입완료.
 * 1단계에서는 잔액 부족 같은 출금 실패가 없다 (실제 계좌 연동 없음).
 */
@Component
@ConditionalOnProperty(name = "daily.contract-steps.enabled", havingValue = "true", matchIfMissing = true)
public class AutoDebitStep implements DailyWorkStep {

    private static final EnumSet<BillStatus> UNPAID = EnumSet.of(BillStatus.ISSUED, BillStatus.OVERDUE);

    private final ContractRepository contracts;
    private final PremiumBillRepository bills;
    private final PremiumPaymentService paymentService;

    public AutoDebitStep(ContractRepository contracts, PremiumBillRepository bills,
                         PremiumPaymentService paymentService) {
        this.contracts = contracts;
        this.bills = bills;
        this.paymentService = paymentService;
    }

    @Override
    public DailyWorkStage stage() {
        return DailyWorkStage.AUTO_DEBIT;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(LocalDate businessDate) {
        for (Contract c : contracts.findByStatusAndAutoDebitTrueOrderByIdAsc(ContractStatus.ACTIVE)) {
            for (PremiumBill b : bills.findByContractIdAndStatusInAndDueDateLessThanEqualOrderByInstallmentNoAsc(
                    c.getId(), UNPAID, businessDate)) {
                if (businessDate.isAfter(b.getGraceEndDate())) {
                    break; // 유예 마감이 지난 청구서는 LAPSE 단계가 실효 처리한다
                }
                paymentService.pay(b.getId(), businessDate, PaidChannel.AUTO_DEBIT, Actor.batch());
            }
        }
    }
}
