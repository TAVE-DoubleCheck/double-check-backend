package kr.tave.minicore.contract.domain;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 납입기일·유예 마감 계산 규칙. 실제 엔진(매일 작업)과 시뮬레이터(예상 계산)가 **같은 함수**를 쓴다.
 * 대조 테스트(예상값 == 실제 엔진)가 이 함수 하나를 기준으로 맞는다.
 */
public final class PremiumSchedule {

    private PremiumSchedule() {
    }

    /**
     * n회차 납입기일 = 계약일 + (n − 1)개월.
     * 계약일 기준으로 매번 더하므로 1/31 계약은 2/28(29), 3/31, 4/30 … 처럼 말일이 유지된다.
     */
    public static LocalDate dueDate(LocalDate contractDate, int installmentNo) {
        if (installmentNo < 1) {
            throw new IllegalArgumentException("회차는 1부터: " + installmentNo);
        }
        return contractDate.plusMonths(installmentNo - 1L);
    }

    /**
     * 유예 마감일 = 납입기일이 속한 달의 다다음 달 말일 (약관 제28조①).
     * 이날까지 안 내면 다음 날 실효. 예: 3/15 → 5/31, 12/10 → 다음 해 2/28(29)
     */
    public static LocalDate graceEndDate(LocalDate dueDate) {
        return dueDate.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth());
    }

    /** 실효일 = 유예 마감일 다음 날 */
    public static LocalDate lapseDate(LocalDate dueDate) {
        return graceEndDate(dueDate).plusDays(1);
    }
}
