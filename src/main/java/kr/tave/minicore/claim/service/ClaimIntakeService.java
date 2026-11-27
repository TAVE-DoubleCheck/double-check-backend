package kr.tave.minicore.claim.service;

// 새 파일 (#33): 보험금 청구 접수 (장해 = 고객, 사망 = 직원 대리).
//   흐름: 계약 확인 → 사실 모으기(사고일 당시 상태 등) → 판정 규칙(ClaimEligibility) → 저장 + 이력 1줄
//   판정 규칙 자체는 ClaimEligibility에 있고, 이 서비스는 DB에서 사실을 모아 넘기기만 한다.

import kr.tave.minicore.auth.repository.UserRepository;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.claim.domain.Claim;
import kr.tave.minicore.claim.domain.ClaimCheck;
import kr.tave.minicore.claim.domain.ClaimEligibility;
import kr.tave.minicore.claim.domain.ClaimHistory;
import kr.tave.minicore.claim.domain.ClaimStatus;
import kr.tave.minicore.claim.domain.ClaimType;
import kr.tave.minicore.claim.domain.ClaimantRelation;
import kr.tave.minicore.claim.repository.ClaimHistoryRepository;
import kr.tave.minicore.claim.repository.ClaimRepository;
import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.repository.ContractRepository;
import kr.tave.minicore.contract.service.ContractStatusService;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 청구 접수 */
@Service
public class ClaimIntakeService {

    private static final String ACTIVE_CLAIM_UNIQUE = "uq_claim_active"; // V5 부분 UNIQUE 이름

    private final ClaimRepository claims;
    private final ClaimHistoryRepository histories;
    private final ContractRepository contracts;
    private final ContractStatusService contractStatus;
    private final UserRepository users;
    private final ClockService clock;

    public ClaimIntakeService(ClaimRepository claims, ClaimHistoryRepository histories, ContractRepository contracts,
                              ContractStatusService contractStatus, UserRepository users, ClockService clock) {
        this.claims = claims;
        this.histories = histories;
        this.contracts = contracts;
        this.contractStatus = contractStatus;
        this.users = users;
        this.clock = clock;
    }

    /** 장해 청구 접수 (고객). 내 계약이 아니면 있는지도 알려 주지 않고 404 */
    @Transactional
    public Claim receiveDisability(LoginUser me, long contractId, LocalDate accidentDate, String description) {
        Contract contract = contracts.findById(contractId)
                .filter(c -> Objects.equals(c.getUserId(), me.id())) // 본인 계약만
                .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND, "계약 없음: " + contractId));
        LocalDate today = clock.today();
        requireNotFuture(accidentDate, today);
        check(contract, ClaimType.DISABILITY, accidentDate);

        String name = users.findById(me.id()).orElseThrow().getName(); // 장해 청구인 = 본인
        Claim claim = Claim.disability(contractId, accidentDate, description, name, me.id(), today);
        return save(claim, me.id(), "장해 청구 접수 (고객)", today);
    }

    /** 사망 청구 대리 접수 (직원). 접수 직원은 로그인 계정으로 기록 */
    @Transactional
    public Claim receiveDeath(LoginUser staff, long contractId, LocalDate deathDate, String claimantName,
                              ClaimantRelation relation) {
        Contract contract = contracts.findById(contractId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND, "계약 없음: " + contractId));
        LocalDate today = clock.today();
        requireNotFuture(deathDate, today);
        if (relation == ClaimantRelation.SELF) { // 사망 청구인은 유족
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "사망 청구인 관계는 SPOUSE·CHILD·PARENT·SIBLING 중 하나");
        }
        check(contract, ClaimType.DEATH, deathDate);
        // 수익자 일치(점검 5)는 접수 때 막지 않고 심사·승인 때 확인 (노션 명세)

        Claim claim = Claim.death(contractId, deathDate, claimantName, relation, staff.id(), today);
        return save(claim, staff.id(), "사망 청구 대리 접수 (직원)", today);
    }

    /** DB에서 사실을 모아 판정 규칙에 넣고, 하나라도 실패하면 409 + 실패 항목 */
    private void check(Contract contract, ClaimType type, LocalDate accidentDate) {
        ClaimEligibility.IntakeFacts facts = new ClaimEligibility.IntakeFacts(
                type,
                accidentDate,
                contractStatus.statusOn(contract.getId(), accidentDate).orElse(null), // ★ 지금 상태가 아니라 사고일 당시 상태
                contract.getContractDate(),
                contract.getPayTermYears(),
                claims.existsByContractIdAndTypeAndStatusNot(contract.getId(), type, ClaimStatus.REJECTED));

        List<ClaimCheck> failed = ClaimEligibility.checkIntake(facts);
        if (!failed.isEmpty()) {
            throw notAcceptable(failed);
        }
    }

    /**
     * 청구 + 접수 이력 1줄 저장.
     * ★ 동시 중복 접수 방어: 두 요청이 점검 6을 동시에 통과해도 DB 부분 UNIQUE가 두 번째를 막는다.
     *   그 DB 오류를 500이 아니라 점검 6 실패(409)로 바꿔 응답한다.
     */
    private Claim save(Claim claim, long actorId, String reason, LocalDate today) {
        Claim saved;
        try {
            saved = claims.saveAndFlush(claim); // flush: UNIQUE 위반을 여기서 바로 확인
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            if (cause.contains(ACTIVE_CLAIM_UNIQUE)) {
                throw notAcceptable(List.of(ClaimCheck.NOT_DUPLICATE));
            }
            throw e; // 다른 제약 위반은 서버 버그 → 500 그대로
        }
        histories.save(new ClaimHistory(saved.getId(), null, ClaimStatus.RECEIVED, actorId, reason, today));
        return saved;
    }

    /** 사고일은 오늘(업무 날짜) 이후일 수 없다 (DB ck_claim_accident_date와 같음) */
    private static void requireNotFuture(LocalDate accidentDate, LocalDate today) {
        if (accidentDate.isAfter(today)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "사고일이 오늘(" + today + ") 이후입니다: " + accidentDate);
        }
    }

    /** 409 CLAIM_NOT_ACCEPTABLE. message = 실패 이유들, details.failedChecks = 점검 이름들 */
    private static ApiException notAcceptable(List<ClaimCheck> failed) {
        String message = failed.stream().map(ClaimCheck::message).collect(Collectors.joining(" / "));
        List<String> names = failed.stream().map(Enum::name).toList();
        return new ApiException(ErrorCode.CLAIM_NOT_ACCEPTABLE, message, Map.of("failedChecks", names));
    }
}
