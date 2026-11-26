package kr.tave.minicore.contract.api;

import kr.tave.minicore.contract.domain.BillStatus;
import kr.tave.minicore.contract.domain.PaidChannel;
import kr.tave.minicore.contract.domain.PremiumBill;

import java.time.LocalDate;

/**
 * 청구서 1장. 화면 표시: ISSUED = 납부 대기 / PAID = 납입 완료 / OVERDUE = 미납
 *
 * @param graceEndDate 이날까지 안 내면 다음 날 실효(2회차~) 또는 불성립(1회차)
 */
public record BillResponse(
        long billId,
        int installmentNo,
        LocalDate dueDate,
        LocalDate graceEndDate,
        long amount,
        BillStatus status,
        LocalDate paidOn,
        PaidChannel paidChannel
) {
    public static BillResponse from(PremiumBill b) {
        return new BillResponse(b.getId(), b.getInstallmentNo(), b.getDueDate(), b.getGraceEndDate(), b.getAmount(),
                b.getStatus(), b.getPaidOn(), b.getPaidChannel());
    }
}
