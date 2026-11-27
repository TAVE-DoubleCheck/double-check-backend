package kr.tave.minicore.contract.service;

import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.domain.Actor;
import kr.tave.minicore.contract.domain.BillStatus;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.domain.PaidChannel;
import kr.tave.minicore.contract.domain.PremiumBill;
import kr.tave.minicore.contract.repository.ContractRepository;
import kr.tave.minicore.contract.repository.PremiumBillRepository;
import kr.tave.minicore.ledger.LedgerEntry;
import kr.tave.minicore.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 보험료 납부 (화면 납부 + 자동이체가 같이 쓴다). 한 트랜잭션에서:
 *  1. 청구서 PAID (낸 날, 경로)
 *  2. 장부에 입금 기록 (경진 님 LedgerService, 같은 트랜잭션 필수)
 *  3. 1회차면 계약 성립(PENDING → ACTIVE, 계약일 = 낸 날), 마지막 회차면 납입완료(ACTIVE → PAID_UP)
 * 하나라도 실패하면 전부 취소된다.
 *
 * 두 번 내기 방지:
 *  - 차례로 두 번 요청 → 두 번째는 이미 PAID라 409 BILL_ALREADY_PAID
 *  - 동시에 두 번 요청 → 둘 다 ISSUED를 읽어도 premium_bill.lock_version(@Version) 때문에 늦은 쪽이
 *    409 CONCURRENT_UPDATE (공통 처리 #31). 장부도 청구서당 1줄 UNIQUE라 이중 기록은 DB가 막는다
 */
@Service
public class PremiumPaymentService {

    private final PremiumBillRepository bills;
    private final ContractRepository contracts;
    private final LedgerService ledgerService;
    private final ContractStatusService contractStatusService;
    private final ClockService clock;

    public PremiumPaymentService(PremiumBillRepository bills, ContractRepository contracts,
                                 LedgerService ledgerService, ContractStatusService contractStatusService,
                                 ClockService clock) {
        this.bills = bills;
        this.contracts = contracts;
        this.ledgerService = ledgerService;
        this.contractStatusService = contractStatusService;
        this.clock = clock;
    }

    public record PaymentResult(PremiumBill bill, ContractStatus contractStatus, LedgerEntry ledgerEntry) {
    }

    /**
     * 고객이 화면에서 납부 (POST /api/bills/{id}/pay). 낸 날 = 오늘 업무 날짜.
     * 내 계약의 청구서가 아니면 있어도 404 (계약 조회와 같은 규칙). 직원은 대신 낼 수 없다.
     */
    @Transactional
    public PaymentResult payByCustomer(LoginUser me, long billId) {
        if (me.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "직원 계정으로는 납부할 수 없습니다.");
        }
        PremiumBill bill = bills.findById(billId)
                .filter(b -> contracts.findById(b.getContractId())
                        .map(c -> c.getUserId() == me.id()).orElse(false))
                .orElseThrow(() -> new ApiException(ErrorCode.BILL_NOT_FOUND, "청구서 없음: " + billId));
        return pay(bill.getId(), clock.today(), PaidChannel.ONLINE, Actor.customer(me.id()));
    }

    /**
     * @param on      낸 날 (API는 clockService.today(), 매일 작업은 처리 중인 날짜)
     * @param channel ONLINE(화면) / AUTO_DEBIT(매일 작업)
     * @param actor   계약 상태가 바뀔 때 이력에 남길 사람 (고객 / BATCH)
     */
    @Transactional
    public PaymentResult pay(long billId, LocalDate on, PaidChannel channel, Actor actor) {
        PremiumBill bill = bills.findById(billId)
                .orElseThrow(() -> new ApiException(ErrorCode.BILL_NOT_FOUND, "청구서 없음: " + billId));
        Contract contract = contracts.findById(bill.getContractId())
                .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND, "계약 없음: " + bill.getContractId()));
        requirePayable(bill, contract, on);

        // 1. 청구서 → 2. 장부 (LedgerRepository가 INSERT 전에 flush하므로 DB에는 PAID가 먼저 반영된다)
        bill.markPaid(on, channel);
        LedgerEntry entry = ledgerService.recordPremiumIncome(bill.getId());

        // 3. 계약 상태
        ContractStatus status = contract.getStatus();
        if (status == ContractStatus.PENDING) {
            status = contractStatusService.activate(contract.getId(), on, actor).getStatus();
        }
        if (bill.getInstallmentNo() == contract.totalInstallments()) {
            status = contractStatusService.markPaidUp(contract.getId(), on, actor).getStatus();
        }
        return new PaymentResult(bill, status, entry);
    }

    /**
     * 낼 수 있는 청구서인지.
     *  - 이미 낸 청구서 X
     *  - 계약이 청약(1회차만) 또는 유지 중
     *  - 유예 마감일(1회차는 신청 후 대기 마감일)이 지나지 않음. 지났으면 다음 날 실효·불성립 대상
     *  - 앞 회차를 모두 냄 (회차 순서대로)
     */
    private void requirePayable(PremiumBill bill, Contract contract, LocalDate on) {
        if (bill.isPaid()) {
            throw new ApiException(ErrorCode.BILL_ALREADY_PAID,
                    bill.getInstallmentNo() + "회차는 " + bill.getPaidOn() + "에 이미 납부했습니다.");
        }
        ContractStatus status = contract.getStatus();
        boolean statusOk = status == ContractStatus.ACTIVE
                || (status == ContractStatus.PENDING && bill.getInstallmentNo() == 1);
        if (!statusOk) {
            throw new ApiException(ErrorCode.BILL_NOT_PAYABLE,
                    "계약이 " + status + " 상태라 보험료를 낼 수 없습니다.");
        }
        if (on.isAfter(bill.getGraceEndDate())) {
            throw new ApiException(ErrorCode.BILL_NOT_PAYABLE,
                    "납부 마감일(" + bill.getGraceEndDate() + ")이 지났습니다.");
        }
        if (bills.existsByContractIdAndInstallmentNoLessThanAndStatusNot(
                contract.getId(), bill.getInstallmentNo(), BillStatus.PAID)) {
            throw new ApiException(ErrorCode.BILL_NOT_PAYABLE, "앞 회차부터 납부해 주세요.");
        }
    }
}
