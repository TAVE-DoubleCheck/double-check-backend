package kr.tave.minicore.contract.service;

import kr.tave.minicore.auth.domain.User;
import kr.tave.minicore.auth.repository.UserRepository;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.ContractProperties;
import kr.tave.minicore.contract.api.ApplyContractRequest;
import kr.tave.minicore.contract.api.ApplyContractResponse;
import kr.tave.minicore.contract.domain.Actor;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.domain.ContractHistory;
import kr.tave.minicore.contract.domain.ContractStatus;
import kr.tave.minicore.contract.domain.PremiumBill;
import kr.tave.minicore.contract.repository.ContractHistoryRepository;
import kr.tave.minicore.contract.repository.ContractRepository;
import kr.tave.minicore.contract.repository.PremiumBillRepository;
import kr.tave.minicore.product.quote.PayTermPlan;
import kr.tave.minicore.product.quote.QuoteResult;
import kr.tave.minicore.product.quote.QuoteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 가입 신청 (청약). 한 트랜잭션에서:
 *  1. 회원 정보(생년월일·성별)로 보험료를 서버에서 다시 계산 (화면 값을 믿지 않음)
 *  2. 화면에서 본 상품 버전·월 보험료와 다르면 409 QUOTE_CHANGED
 *  3. 계산 결과를 그대로 복사해 계약 저장 (PENDING) + 이력 "가입 신청" + 1회차 청구서
 * 하나라도 실패하면 셋 다 저장되지 않는다.
 */
@Service
public class ContractApplyService {

    static final String APPLY_REASON = "가입 신청";

    private final UserRepository users;
    private final QuoteService quoteService;
    private final ContractRepository contracts;
    private final ContractHistoryRepository histories;
    private final PremiumBillRepository bills;
    private final ContractProperties properties;

    public ContractApplyService(UserRepository users, QuoteService quoteService, ContractRepository contracts,
                                ContractHistoryRepository histories, PremiumBillRepository bills,
                                ContractProperties properties) {
        this.users = users;
        this.quoteService = quoteService;
        this.contracts = contracts;
        this.histories = histories;
        this.bills = bills;
        this.properties = properties;
    }

    @Transactional
    public ApplyContractResponse apply(LoginUser me, ApplyContractRequest req) {
        if (me.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "직원 계정으로는 가입할 수 없습니다.");
        }
        User user = users.findById(me.id())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다."));

        // 1. 서버에서 다시 계산. 업무 날짜(= 신청일)는 QuoteService가 ClockService로 정한다
        QuoteResult quote = quoteService.quote(req.productCode(), user.getBirthDate(), user.getSex(),
                payTermPlan(req), req.insureTermCode(), req.amount());

        // 2. 화면에서 본 값과 비교 (그사이 상품 버전·요율이 바뀌었거나 업무 날짜가 넘어가 보험나이가 바뀐 경우)
        if (quote.versionId() != req.expectedVersionId() || quote.monthlyPremium() != req.expectedMonthlyPremium()) {
            throw new ApiException(ErrorCode.QUOTE_CHANGED,
                    "보험료가 다시 계산되었습니다. 상품 버전 " + quote.versionId() + ", 월 보험료 "
                            + quote.monthlyPremium() + "원을 확인 후 다시 신청해 주세요.");
        }

        // 3. 계약 + 이력 + 1회차 청구서
        Contract contract = contracts.save(
                Contract.apply(user.getId(), quote, req.beneficiaryName().trim(), req.beneficiaryRelation()));
        LocalDate appliedOn = quote.businessDate();
        histories.save(new ContractHistory(contract.getId(), null, ContractStatus.PENDING, APPLY_REASON,
                Actor.customer(user.getId()), appliedOn));
        PremiumBill first = bills.save(PremiumBill.issue(contract, 1, appliedOn,
                appliedOn.plusDays(properties.firstPremiumWaitDays())));

        return ApplyContractResponse.of(contract, quote.productCode(), quote.payTermPlan(), first);
    }

    /** payTermYears / payTermPlan 중 정확히 하나 (보험료 계산 API와 같은 규칙) */
    private static PayTermPlan payTermPlan(ApplyContractRequest req) {
        boolean hasYears = req.payTermYears() != null;
        boolean hasPlan = req.payTermPlan() != null && !req.payTermPlan().isBlank();
        if (hasYears == hasPlan) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "payTermYears 또는 payTermPlan 중 정확히 하나를 보내세요.");
        }
        return hasYears ? PayTermPlan.fixedYears(req.payTermYears()) : PayTermPlan.parse(req.payTermPlan());
    }
}
