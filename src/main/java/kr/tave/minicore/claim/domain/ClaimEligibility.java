package kr.tave.minicore.claim.domain;

// 새 파일 (#33): 청구 접수 단계 자동 점검 1·2·3·6 판정 규칙.
//   DB·스프링 없이 값만 받아 판정하는 순수 함수다. 그래서
//     - 실제 접수(ClaimIntakeService)는 DB에서 사실을 모아 이 함수에 넣고,
//     - 시뮬레이터(「이 날 사고 나면?」)는 예상 사실을 넣어 같은 규칙으로 결과를 본다.
//   두 곳이 같은 코드를 쓰므로 "예상 결과 = 실제 처리 결과"를 테스트로 대조할 수 있다 (방향 수정안 6장).
// 변경 (#35): 승인 점검 checkApproval 추가 (접수 점검 + 4 지급률 50% + 5 수익자 일치).

import kr.tave.minicore.contract.domain.ContractStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 접수·승인 단계 판정 */
public final class ClaimEligibility {

    private ClaimEligibility() {
    }

    /**
     * 판정에 필요한 사실 묶음.
     *
     * @param type                   청구 종류
     * @param accidentDate           사고일 (사망은 사망일)
     * @param statusOnAccidentDate   사고일 끝 시점의 계약 상태. 가입 신청 전 날짜면 null
     * @param contractDate           계약일 = 첫 보험료 낸 날 = 보장 시작일. 아직 성립 전이면 null
     * @param payTermYears           납입기간(년)
     * @param hasActiveSameTypeClaim 같은 계약에 거절되지 않은 같은 종류 청구가 이미 있는가
     */
    public record IntakeFacts(ClaimType type, LocalDate accidentDate, ContractStatus statusOnAccidentDate,
                              LocalDate contractDate, int payTermYears, boolean hasActiveSameTypeClaim) {
    }

    /**
     * 접수 점검. 실패한 항목을 점검 번호 순서로 돌려준다. 빈 목록이면 접수 가능.
     * 하나만 보고 멈추지 않고 전부 검사한다 → 화면에 이유를 한 번에 보여 줄 수 있다.
     */
    public static List<ClaimCheck> checkIntake(IntakeFacts f) {
        List<ClaimCheck> failed = new ArrayList<>();

        // ★ 1. 보장 여부는 "청구한 날"이 아니라 "사고 난 날"의 계약 상태로 판단 (1주차 회의 결정, 약관 제28조①)
        //      예: 6/1 실효 → 5/31 사고는 ACTIVE라 보장, 6/1·6/2 사고는 LAPSED라 보장 안 됨
        if (f.statusOnAccidentDate() == null || !f.statusOnAccidentDate().isCovered()) {
            failed.add(ClaimCheck.COVERED_ON_ACCIDENT_DATE);
        }

        // 2. 계약 성립(첫 보험료 납부) 당일부터 보장 (약관 제25조①). 계약일 없음 = 아직 성립 전
        boolean started = f.contractDate() != null && !f.accidentDate().isBefore(f.contractDate());
        if (!started) {
            failed.add(ClaimCheck.AFTER_CONTRACT_START);
        }

        // 3. 장해는 납입기간 중 사고만 (납입면제는 남은 보험료가 있어야 의미가 있음, 약관 제4조①)
        //    납입 끝 = 계약일 + 납입년수 (그날부터는 납입기간 밖). 계약일이 없으면 2번에서 이미 실패라 건너뜀
        if (f.type() == ClaimType.DISABILITY && f.contractDate() != null
                && !f.accidentDate().isBefore(f.contractDate().plusYears(f.payTermYears()))) {
            failed.add(ClaimCheck.WITHIN_PAY_TERM);
        }

        // 6. 거절 안 된 같은 종류 청구는 계약당 1건 (DB 부분 UNIQUE uq_claim_active와 같은 규칙)
        if (f.hasActiveSameTypeClaim()) {
            failed.add(ClaimCheck.NOT_DUPLICATE);
        }
        return failed;
    }

    /**
     * #35: 승인 판정에 필요한 사실 묶음 = 접수 사실 + 심사 결과.
     *
     * @param intake              접수 때와 같은 사실 (승인 시점에 다시 모은 값). 중복 여부는 "이 청구 말고 다른 청구"
     * @param latestDisabilityRate 최신 장해지급률 판정 값. 판정 기록이 없으면 null (장해만 사용)
     * @param beneficiaryMatches  청구인 이름·관계 = 계약 수익자 이름·관계 (사망만 사용)
     */
    public record ApprovalFacts(IntakeFacts intake, Integer latestDisabilityRate, boolean beneficiaryMatches) {
    }

    /**
     * #35: 승인 점검 = 접수 점검 1·2·3·6 다시 + 점검 4·5. 실패 항목을 점검 번호 순서로 돌려준다.
     * ★ 승인 때 처음부터 다시 검사: 접수한 뒤 판정이 바뀌거나 다른 청구가 생겼을 수 있어서 (노션 명세 「청구 승인」)
     */
    public static List<ClaimCheck> checkApproval(ApprovalFacts f) {
        List<ClaimCheck> failed = new ArrayList<>(checkIntake(f.intake()));
        ClaimType type = f.intake().type();

        // 4. 장해: 최신 판정이 50 이상 (50 포함, 약관 제4조①). 판정이 없으면 실패
        if (type == ClaimType.DISABILITY && (f.latestDisabilityRate() == null
                || f.latestDisabilityRate() < ClaimAssessment.WAIVER_THRESHOLD)) {
            failed.add(ClaimCheck.DISABILITY_RATE_50);
        }
        // 5. 사망: 청구인 = 가입 때 정한 수익자
        if (type == ClaimType.DEATH && !f.beneficiaryMatches()) {
            failed.add(ClaimCheck.BENEFICIARY_MATCH);
        }
        failed.sort(null); // enum 선언 순서 = 점검 번호 순서 (1·2·3·4·5·6)
        return failed;
    }
}
