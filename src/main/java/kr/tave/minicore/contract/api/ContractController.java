package kr.tave.minicore.contract.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.contract.service.ContractApplyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "계약", description = "가입 신청")
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractApplyService applyService;

    public ContractController(ContractApplyService applyService) {
        this.applyService = applyService;
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
}
