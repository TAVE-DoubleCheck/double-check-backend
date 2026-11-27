package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 접수 판정 규칙 단위 테스트 (DB·스프링 없음).
//   방향 수정안 5-1의 예를 그대로 쓴다: 3/15 계약 → (미납) → 5/31 유예 종료 → 6/1 실효.
//   5/20 사고는 보장, 6/2 사고는 보장 안 됨.
// 추가 (#35): 승인 점검 (4 지급률 50%, 5 수익자 일치, 실패 순서).

import kr.tave.minicore.claim.domain.ClaimEligibility.ApprovalFacts;
import kr.tave.minicore.claim.domain.ClaimEligibility.IntakeFacts;
import kr.tave.minicore.contract.domain.ContractStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static kr.tave.minicore.claim.domain.ClaimCheck.AFTER_CONTRACT_START;
import static kr.tave.minicore.claim.domain.ClaimCheck.BENEFICIARY_MATCH;
import static kr.tave.minicore.claim.domain.ClaimCheck.DISABILITY_RATE_50;
import static kr.tave.minicore.claim.domain.ClaimCheck.COVERED_ON_ACCIDENT_DATE;
import static kr.tave.minicore.claim.domain.ClaimCheck.NOT_DUPLICATE;
import static kr.tave.minicore.claim.domain.ClaimCheck.WITHIN_PAY_TERM;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimEligibilityTest {

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 3, 15); // 계약일 = 첫 보험료 낸 날
    private static final int PAY_TERM_YEARS = 10;

    /** 실효 전 사고(5/20, 그날 상태 ACTIVE) → 통과 */
    @Test
    void 실효_전_사고는_접수_가능() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DISABILITY, "2026-05-20", ContractStatus.ACTIVE, false)))
                .isEmpty();
    }

    /** 실효 후 사고(6/2, 그날 상태 LAPSED) → 점검 1 실패 */
    @Test
    void 실효_후_사고는_보장_아님() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DISABILITY, "2026-06-02", ContractStatus.LAPSED, false)))
                .containsExactly(COVERED_ON_ACCIDENT_DATE);
    }

    /** 납입면제·납입완료 상태에서도 보장은 계속된다 */
    @Test
    void 납입면제_납입완료도_보장_중() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DEATH, "2026-08-01", ContractStatus.PREMIUM_WAIVED, false)))
                .isEmpty();
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DEATH, "2026-08-01", ContractStatus.PAID_UP, false)))
                .isEmpty();
    }

    /** 청약(PENDING) 중, 첫 보험료 전 사고 → 점검 1·2 둘 다 실패로 한 번에 알려 줌 */
    @Test
    void 계약_성립_전_사고는_1번_2번_실패() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DISABILITY, "2026-03-12", ContractStatus.PENDING, false)))
                .containsExactly(COVERED_ON_ACCIDENT_DATE, AFTER_CONTRACT_START);
    }

    /** 가입 신청 전 날짜(그날 상태 없음) → 보장 아님 */
    @Test
    void 가입_전_날짜는_보장_아님() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DEATH, "2026-01-01", null, false)))
                .contains(COVERED_ON_ACCIDENT_DATE, AFTER_CONTRACT_START);
    }

    /** 계약일 당일 사고는 보장 (약관 제25조①: 첫 보험료 받은 때부터) */
    @Test
    void 계약일_당일_사고는_보장() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DISABILITY, "2026-03-15", ContractStatus.ACTIVE, false)))
                .isEmpty();
    }

    /** 납입기간(10년) 끝난 날부터의 장해 → 점검 3 실패. 하루 전은 통과 */
    @Test
    void 납입기간_이후_장해는_3번_실패() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DISABILITY, "2036-03-14", ContractStatus.ACTIVE, false)))
                .isEmpty();
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DISABILITY, "2036-03-15", ContractStatus.PAID_UP, false)))
                .containsExactly(WITHIN_PAY_TERM);
    }

    /** 점검 3은 장해만. 사망은 납입기간이 끝나도 보장 (종신) */
    @Test
    void 사망은_납입기간과_무관() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DEATH, "2040-01-01", ContractStatus.PAID_UP, false)))
                .isEmpty();
    }

    /** 진행 중인 같은 종류 청구가 있으면 점검 6 실패 */
    @Test
    void 중복_청구는_6번_실패() {
        assertThat(ClaimEligibility.checkIntake(facts(ClaimType.DISABILITY, "2026-05-20", ContractStatus.ACTIVE, true)))
                .containsExactly(NOT_DUPLICATE);
    }

    // ── #35 승인 점검 ──

    /** 장해: 최신 판정 60 → 통과, 50(경계값 포함) → 통과, 40 → 점검 4 실패, 판정 없음 → 실패 */
    @Test
    void 장해_승인은_지급률_50_이상() {
        IntakeFacts ok = facts(ClaimType.DISABILITY, "2026-05-20", ContractStatus.ACTIVE, false);
        assertThat(ClaimEligibility.checkApproval(new ApprovalFacts(ok, 60, false))).isEmpty();
        assertThat(ClaimEligibility.checkApproval(new ApprovalFacts(ok, 50, false))).isEmpty();
        assertThat(ClaimEligibility.checkApproval(new ApprovalFacts(ok, 40, false))).containsExactly(DISABILITY_RATE_50);
        assertThat(ClaimEligibility.checkApproval(new ApprovalFacts(ok, null, false))).containsExactly(DISABILITY_RATE_50);
    }

    /** 사망: 수익자 불일치 → 점검 5 실패 (지급률은 보지 않음) */
    @Test
    void 사망_승인은_수익자_일치() {
        IntakeFacts ok = facts(ClaimType.DEATH, "2026-05-20", ContractStatus.ACTIVE, false);
        assertThat(ClaimEligibility.checkApproval(new ApprovalFacts(ok, null, true))).isEmpty();
        assertThat(ClaimEligibility.checkApproval(new ApprovalFacts(ok, null, false))).containsExactly(BENEFICIARY_MATCH);
    }

    /** 여러 개 실패하면 점검 번호 순서 (1 → 4 → 6) */
    @Test
    void 승인_실패_항목은_점검_번호_순서() {
        IntakeFacts bad = facts(ClaimType.DISABILITY, "2026-06-02", ContractStatus.LAPSED, true);
        assertThat(ClaimEligibility.checkApproval(new ApprovalFacts(bad, 30, false)))
                .containsExactly(COVERED_ON_ACCIDENT_DATE, DISABILITY_RATE_50, NOT_DUPLICATE);
    }

    private static IntakeFacts facts(ClaimType type, String accidentDate, ContractStatus status, boolean duplicate) {
        return new IntakeFacts(type, LocalDate.parse(accidentDate), status, CONTRACT_DATE, PAY_TERM_YEARS, duplicate);
    }
}
