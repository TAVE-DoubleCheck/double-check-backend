package kr.tave.minicore.contract.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 납입기일·유예 마감 규칙 (엔진과 시뮬레이터가 같이 쓰는 함수) */
class PremiumScheduleTest {

    @Test
    void 납입기일은_계약일에서_회차만큼_개월을_더한다() {
        LocalDate contractDate = LocalDate.of(2026, 3, 15);
        assertThat(PremiumSchedule.dueDate(contractDate, 1)).isEqualTo("2026-03-15");
        assertThat(PremiumSchedule.dueDate(contractDate, 2)).isEqualTo("2026-04-15");
        assertThat(PremiumSchedule.dueDate(contractDate, 13)).isEqualTo("2027-03-15");
    }

    @Test
    void 말일_계약은_짧은_달에_말일로_내고_다시_31일로_돌아온다() {
        LocalDate contractDate = LocalDate.of(2026, 1, 31);
        assertThat(PremiumSchedule.dueDate(contractDate, 2)).isEqualTo("2026-02-28");
        assertThat(PremiumSchedule.dueDate(contractDate, 3)).isEqualTo("2026-03-31");
        assertThat(PremiumSchedule.dueDate(contractDate, 4)).isEqualTo("2026-04-30");
        assertThat(PremiumSchedule.dueDate(LocalDate.of(2027, 1, 31), 2)).isEqualTo("2027-02-28");
        assertThat(PremiumSchedule.dueDate(LocalDate.of(2028, 1, 31), 2)).isEqualTo("2028-02-29");
    }

    @Test
    void 유예_마감은_납입기일_달의_다다음_달_말일이고_다음_날_실효() {
        // 발표 7장 예시: 3/15 미납 → 5/31 유예 종료 → 6/1 실효
        assertThat(PremiumSchedule.graceEndDate(LocalDate.of(2026, 3, 15))).isEqualTo("2026-05-31");
        assertThat(PremiumSchedule.lapseDate(LocalDate.of(2026, 3, 15))).isEqualTo("2026-06-01");
        // 납입기일이 1일이든 말일이든 같은 달이면 마감은 같다
        assertThat(PremiumSchedule.graceEndDate(LocalDate.of(2026, 3, 1))).isEqualTo("2026-05-31");
        assertThat(PremiumSchedule.graceEndDate(LocalDate.of(2026, 3, 31))).isEqualTo("2026-05-31");
        // 해를 넘기면 2월 말일 (윤년 포함)
        assertThat(PremiumSchedule.graceEndDate(LocalDate.of(2026, 12, 10))).isEqualTo("2027-02-28");
        assertThat(PremiumSchedule.graceEndDate(LocalDate.of(2027, 12, 10))).isEqualTo("2028-02-29");
    }

    @Test
    void 회차는_1부터() {
        assertThatThrownBy(() -> PremiumSchedule.dueDate(LocalDate.of(2026, 3, 15), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
