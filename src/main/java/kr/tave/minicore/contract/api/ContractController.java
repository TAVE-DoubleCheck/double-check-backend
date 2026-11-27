package kr.tave.minicore.contract.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.contract.service.ContractApplyService;
import kr.tave.minicore.contract.service.ContractQueryService;
import kr.tave.minicore.contract.service.ContractSettingService;
import kr.tave.minicore.contract.domain.Contract;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@Tag(name = "계약", description = "가입 신청·내 계약 조회·자동이체 설정")
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractApplyService applyService;
    private final ContractQueryService queryService;
    private final ContractSettingService settingService;

    public ContractController(ContractApplyService applyService, ContractQueryService queryService,
                              ContractSettingService settingService) {
        this.applyService = applyService;
        this.queryService = queryService;
        this.settingService = settingService;
    }

    @Operation(summary = "가입 신청 (고객)",
            description = "보험료 계산 결과(versionId, monthlyPremium)를 함께 보냅니다. 서버 재계산 값과 다르면 409 QUOTE_CHANGED. "
                    + "성공하면 청약(PENDING) 계약과 1회차 청구서가 만들어지고, 1회차를 내는 날 계약이 성립합니다.")
    @PostMapping
    public ResponseEntity<ApplyContractResponse> apply(@AuthenticationPrincipal LoginUser me,
                                                       @Valid @RequestBody ApplyContractRequest req) {
        ApplyContractResponse res = applyService.apply(me, req);
        return ResponseEntity.created(URI.create("/api/contracts/" + res.contractId())).body(res);
    }

    @Operation(summary = "내 계약 목록", description = "최근 신청 순. 납입 회차 진행(paidInstallments / totalInstallments) 포함")
    @GetMapping
    public List<ContractSummaryResponse> myContracts(@AuthenticationPrincipal LoginUser me) {
        return queryService.myContracts(me);
    }

    @Operation(summary = "계약 상세", description = "가입 시점 스냅샷 + 현재 상태 + 다음에 낼 청구서. 남의 계약은 404 (직원은 모두 조회 가능)")
    @GetMapping("/{contractId}")
    public ContractDetailResponse detail(@AuthenticationPrincipal LoginUser me, @PathVariable long contractId) {
        return queryService.detail(me, contractId);
    }

    @Operation(summary = "계약의 청구서 목록", description = "1회차부터. ISSUED = 납부 대기 / PAID = 납입 완료 / OVERDUE = 미납")
    @GetMapping("/{contractId}/bills")
    public List<BillResponse> bills(@AuthenticationPrincipal LoginUser me, @PathVariable long contractId) {
        return queryService.bills(me, contractId);
    }

    @Operation(summary = "계약 상태 이력", description = "오래된 순. 가입 신청 → 계약 성립 → … ")
    @GetMapping("/{contractId}/history")
    public List<ContractHistoryResponse> history(@AuthenticationPrincipal LoginUser me,
                                                 @PathVariable long contractId) {
        return queryService.history(me, contractId);
    }

    @Operation(summary = "자동이체 켜기/끄기 (고객)",
            description = "청약·유지 중인 본인 계약만. 켜 두면 매일 작업이 납입기일에 자동 납부합니다. 그 외 상태는 409 INVALID_CONTRACT_STATUS")
    @PutMapping("/{contractId}/auto-debit")
    public AutoDebitResponse changeAutoDebit(@AuthenticationPrincipal LoginUser me, @PathVariable long contractId,
                                             @Valid @RequestBody AutoDebitRequest req) {
        Contract c = settingService.changeAutoDebit(me, contractId, req.enabled());
        return new AutoDebitResponse(c.getId(), c.isAutoDebit());
    }
}
