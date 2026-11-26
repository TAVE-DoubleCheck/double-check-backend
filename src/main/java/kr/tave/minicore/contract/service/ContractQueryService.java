package kr.tave.minicore.contract.service;

import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.api.BillResponse;
import kr.tave.minicore.contract.api.ContractDetailResponse;
import kr.tave.minicore.contract.api.ContractHistoryResponse;
import kr.tave.minicore.contract.api.ContractSummaryResponse;
import kr.tave.minicore.contract.domain.BillStatus;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.repository.ContractHistoryRepository;
import kr.tave.minicore.contract.repository.ContractRepository;
import kr.tave.minicore.contract.repository.PremiumBillRepository;
import kr.tave.minicore.product.domain.Product;
import kr.tave.minicore.product.domain.ProductVersion;
import kr.tave.minicore.product.quote.PayTermPlan;
import kr.tave.minicore.product.repository.ProductRepository;
import kr.tave.minicore.product.repository.ProductVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 계약 조회 (읽기 전용).
 * 고객은 자기 계약만 본다. 남의 계약 id를 넣으면 "있다"는 사실도 알려 주지 않도록 404 (이슈 #8).
 * 직원은 청구 심사 등에 필요하므로 모든 계약의 상세·청구서·이력을 볼 수 있다.
 */
@Service
@Transactional(readOnly = true)
public class ContractQueryService {

    private final ContractRepository contracts;
    private final PremiumBillRepository bills;
    private final ContractHistoryRepository histories;
    private final ProductVersionRepository versions;
    private final ProductRepository products;

    public ContractQueryService(ContractRepository contracts, PremiumBillRepository bills,
                                ContractHistoryRepository histories, ProductVersionRepository versions,
                                ProductRepository products) {
        this.contracts = contracts;
        this.bills = bills;
        this.histories = histories;
        this.versions = versions;
        this.products = products;
    }

    /** 내 계약 목록 (최근 신청 순) */
    public List<ContractSummaryResponse> myContracts(LoginUser me) {
        Map<Long, ProductInfo> cache = new HashMap<>();
        return contracts.findByUserIdOrderByIdDesc(me.id()).stream()
                .map(c -> {
                    ProductInfo p = cache.computeIfAbsent(c.getVersionId(), this::productOf);
                    return new ContractSummaryResponse(c.getId(), c.getContractNo(), p.code(), p.name(),
                            c.getStatus(), c.getInsuredAmount(), c.getMonthlyPremium(), c.getAppliedOn(),
                            c.getContractDate(), paidCount(c), totalInstallments(c));
                })
                .toList();
    }

    public ContractDetailResponse detail(LoginUser me, long contractId) {
        Contract c = findVisible(me, contractId);
        ProductInfo p = productOf(c.getVersionId());
        BillResponse next = bills.findByContractIdOrderByInstallmentNoAsc(c.getId()).stream()
                .filter(b -> b.getStatus() != BillStatus.PAID)
                .findFirst()
                .map(BillResponse::from)
                .orElse(null);
        return new ContractDetailResponse(c.getId(), c.getContractNo(), p.code(), p.name(), c.getVersionId(),
                c.getStatus(), c.getInsuranceAge(), String.valueOf(c.getSex()), c.getInsuredAmount(),
                c.getMonthlyPremium(), payTermPlan(c), c.getPayTermYears(), c.getInsureTermCode(), c.getAppliedOn(),
                c.getContractDate(), c.getBillingDay() == null ? null : c.getBillingDay().intValue(), c.isAutoDebit(),
                c.getBeneficiaryName(), c.getBeneficiaryRelation(), paidCount(c), totalInstallments(c), next);
    }

    /** 청구서 전체 (1회차부터) */
    public List<BillResponse> bills(LoginUser me, long contractId) {
        Contract c = findVisible(me, contractId);
        return bills.findByContractIdOrderByInstallmentNoAsc(c.getId()).stream().map(BillResponse::from).toList();
    }

    /** 상태 이력 (오래된 순) */
    public List<ContractHistoryResponse> history(LoginUser me, long contractId) {
        Contract c = findVisible(me, contractId);
        return histories.findByContractIdOrderByIdAsc(c.getId()).stream()
                .map(ContractHistoryResponse::from).toList();
    }

    /** 내 계약이거나 직원이면 계약, 아니면 404 */
    private Contract findVisible(LoginUser me, long contractId) {
        return contracts.findById(contractId)
                .filter(c -> me.isAdmin() || Objects.equals(c.getUserId(), me.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND, "계약 없음: " + contractId));
    }

    private long paidCount(Contract c) {
        return bills.countByContractIdAndStatus(c.getId(), BillStatus.PAID);
    }

    private static int totalInstallments(Contract c) {
        return c.getPayTermYears() * 12;
    }

    private static PayTermPlan payTermPlan(Contract c) {
        return PayTermPlan.Type.valueOf(c.getPayTermPlanType()) == PayTermPlan.Type.FIXED_YEARS
                ? PayTermPlan.fixedYears(c.getPayTermPlanValue())
                : PayTermPlan.toAge(c.getPayTermPlanValue());
    }

    /** 계약이 가리키는 상품 버전 → 상품코드·이름 (가입 시점 버전 고정이라 버전 id로 찾는다) */
    private ProductInfo productOf(long versionId) {
        String code = versions.findById(versionId).map(ProductVersion::getProductCode).orElse(null);
        String name = code == null ? null : products.findById(code).map(Product::getShortName).orElse(null);
        return new ProductInfo(code, name);
    }

    private record ProductInfo(String code, String name) {
    }
}
