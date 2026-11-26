package kr.tave.minicore.contract.service;

import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.domain.Actor;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractHistory;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.repository.ContractHistoryRepository;
import kr.tave.minicore.contract.repository.ContractRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 계약 상태를 바꾸는 유일한 입구 (docs/contract-status.md).
 * 상태 변경과 이력 1줄을 같은 트랜잭션에서 저장한다. 호출한 쪽에 트랜잭션이 있으면 그 안에 합류하므로
 * 지급(장부 출금)·실효(배치 하루 처리)와 함께 저장되거나 함께 취소된다.
 *
 * 날짜(on)는 호출한 쪽이 넘긴다: API는 clockService.today(), 일 배치는 처리 중인 날짜.
 * 이 서비스가 직접 시계를 읽지 않는 이유는 배치가 "처리 중인 날"과 시계 값이 다를 수 있어서다.
 */
@Service
public class ContractStatusService {

    private final ContractRepository contracts;
    private final ContractHistoryRepository histories;

    public ContractStatusService(ContractRepository contracts, ContractHistoryRepository histories) {
        this.contracts = contracts;
        this.histories = histories;
    }

    // ── 건아 (납부) ──

    /** 1회차 납부: 청약 → 유지. 계약일·매달 납입일이 이날로 정해진다 (약관 제25조①) */
    @Transactional
    public Contract activate(long contractId, LocalDate paidOn, Actor actor) {
        Contract contract = load(contractId);
        ContractStatus from = contract.getStatus();
        contract.activate(paidOn);
        record(contract, from, "1회차 납부 (계약 성립·보장 시작)", actor, paidOn);
        return contract;
    }

    /** 마지막 회차 납부: 유지 → 납입완료 */
    @Transactional
    public Contract markPaidUp(long contractId, LocalDate on, Actor actor) {
        return change(contractId, ContractStatus.PAID_UP, "최종 회차 납입 완료", actor, on);
    }

    // ── 일 배치 (건아 로직, 경진 엔진이 호출) ──

    /** 유예기간이 끝날 때까지 미납: 유지 → 실효. on = 유예 마감일 다음 날 (약관 제28조①) */
    @Transactional
    public Contract lapse(long contractId, LocalDate on, long premiumBillId) {
        return change(contractId, ContractStatus.LAPSED,
                "유예기간 경과 미납 (약관 제28조①, 청구서 #" + premiumBillId + ")", Actor.batch(), on);
    }

    /** 1회차를 내지 않음: 청약 → 불성립 */
    @Transactional
    public Contract voidContract(long contractId, LocalDate on) {
        return change(contractId, ContractStatus.VOID, "1회차 미납 (계약 불성립)", Actor.batch(), on);
    }

    // ── 혜원 (청구·지급) ──

    /** 장해 청구 승인: 유지 → 납입면제. on = claim.decided_on (이날 이후 청구서부터 면제, 회의 B-5) */
    @Transactional
    public Contract waivePremium(long contractId, LocalDate on, long staffId, long claimId) {
        return change(contractId, ContractStatus.PREMIUM_WAIVED,
                "장해 납입면제 승인 (약관 제4조①, 청구 #" + claimId + ")", Actor.admin(staffId), on);
    }

    /** 사망보험금 지급: 유지·납입완료·납입면제 → 종료. on = payout.paid_on */
    @Transactional
    public Contract terminate(long contractId, LocalDate on, long staffId, long payoutId) {
        return change(contractId, ContractStatus.TERMINATED,
                "사망보험금 지급 (지급 #" + payoutId + ")", Actor.admin(staffId), on);
    }

    // ── 조회 ──

    /**
     * 그 날짜에 계약이 어떤 상태였는가. 청구 자동 점검 1번(사고 난 날 보장 중이었나)에 쓴다.
     * 그날 상태가 바뀌었으면 바뀐 뒤 상태를 준다. 가입 전 날짜면 빈 값.
     * 예: 6/1 실효 → 5/31 사고는 ACTIVE(보장), 6/1 사고는 LAPSED (약관 제28조① 단서는 "해지 전" 사고만 보상)
     */
    @Transactional(readOnly = true)
    public Optional<ContractStatus> statusOn(long contractId, LocalDate date) {
        return histories
                .findFirstByContractIdAndOccurredOnLessThanEqualOrderByOccurredOnDescIdDesc(contractId, date)
                .map(ContractHistory::getToStatus);
    }

    private Contract change(long contractId, ContractStatus next, String reason, Actor actor, LocalDate on) {
        Contract contract = load(contractId);
        ContractStatus from = contract.getStatus();
        contract.changeStatus(next);
        record(contract, from, reason, actor, on);
        return contract;
    }

    private void record(Contract contract, ContractStatus from, String reason, Actor actor, LocalDate on) {
        histories.save(new ContractHistory(contract.getId(), from, contract.getStatus(), reason, actor, on));
    }

    private Contract load(long contractId) {
        return contracts.findById(contractId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND, "계약 없음: " + contractId));
    }
}
