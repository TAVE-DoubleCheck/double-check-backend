package kr.tave.minicore.contract.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.contract.service.PremiumPaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "납부", description = "보험료 청구서 납부")
@RestController
@RequestMapping("/api/bills")
public class BillController {

    private final PremiumPaymentService paymentService;

    public BillController(PremiumPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "보험료 납부 (고객)",
            description = "낸 날 = 오늘 업무 날짜. 1회차를 내면 계약 성립(낸 날 = 계약일 = 보장 시작), 마지막 회차면 납입완료. "
                    + "이미 낸 청구서 409 BILL_ALREADY_PAID, 앞 회차 미납·마감 지남·계약 상태 409 BILL_NOT_PAYABLE, "
                    + "동시에 두 번 누름 409 CONCURRENT_UPDATE")
    @PostMapping("/{billId}/pay")
    public PaymentResponse pay(@AuthenticationPrincipal LoginUser me, @PathVariable long billId) {
        return PaymentResponse.from(paymentService.payByCustomer(me, billId));
    }
}
