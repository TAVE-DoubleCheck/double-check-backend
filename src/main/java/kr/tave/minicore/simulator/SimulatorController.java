package kr.tave.minicore.simulator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.tave.minicore.product.domain.Sex;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 가입 예정자용 예상 계산 (비로그인, 읽기 전용). 시스템 날짜를 바꾸지 않고 미래 시점 값을 계산만 해서 보여준다. */
@Tag(name = "시뮬레이터", description = "표준형·해약환급금 50%지급형 시점별 예상 계산 (읽기 전용)")
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final EstimateService estimate;

    public SimulatorController(EstimateService estimate) {
        this.estimate = estimate;
    }

    @Operation(summary = "예상 계산 v0 (두 유형 월 보험료 + 1·3·5·7·10·20년·납입완료 시점별 낸 돈·남은 돈·해약환급금·사망보험금. "
            + "날짜 YYYY-MM-DD, 금액 원 단위 정수, 납입기간 10|15|20|30)")
    @GetMapping("/estimate")
    public EstimateResponse estimate(
            @RequestParam JoinType joinType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthDate,
            @RequestParam Sex sex,
            @RequestParam int payTermYears,
            @RequestParam long amount) {
        return estimate.estimate(joinType, birthDate, sex, payTermYears, amount);
    }
}
