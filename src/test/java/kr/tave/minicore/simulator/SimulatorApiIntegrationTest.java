package kr.tave.minicore.simulator;

import kr.tave.minicore.clock.ClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예상 계산 v0를 실제 적재본 덤프(seed) 위에서 검증한다 (명세 7장 통합 테스트).
 * 업무 날짜 2026-11-27 고정 → 1986-10-01생 = 보험나이 40세 (만 40년 1월 26일, 끝수 버림).
 *
 * 핵심 확인(명세 8장 #6): DB 요율로 계산한 월 보험료가 예시표 역산값 18,190 / 16,580과 같은지.
 * 다르면 이 테스트가 실패해야 하고, 코드를 기대값에 맞추지 말고 데이터 정합성 이슈로 보고한다.
 */
@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "ingest.service-key=test-key"
})
@AutoConfigureMockMvc
@Testcontainers
class SimulatorApiIntegrationTest {

    private static final String BUSINESS_DATE = "2026-11-27";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        ClockService fixedClockService() {
            return () -> LocalDate.parse(BUSINESS_DATE);
        }
    }

    @Autowired
    MockMvc mvc;

    private static boolean seeded = false;

    @BeforeEach
    void loadSeed() throws Exception {
        if (seeded) {
            return;
        }
        // reference_seed(V7 예시표)는 product FK가 있어 product_seed 다음에 넣는다
        for (String file : new String[] {"product_seed.sql", "reference_seed.sql"}) {
            postgres.copyFileToContainer(MountableFile.forHostPath(Path.of("seed/" + file)), "/tmp/" + file);
            var result = postgres.execInContainer(
                    "psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                    "-v", "ON_ERROR_STOP=1", "-f", "/tmp/" + file);
            assertThat(result.getExitCode()).as("%s 복원 실패: %s", file, result.getStderr()).isZero();
        }
        seeded = true;
    }

    /** 1종 남 40세 1,000만 30년납 (예시표 대표 조건). 비로그인 호출 */
    private ResultActions exampleEstimate() throws Exception {
        return estimate("GENERAL", "1986-10-01", "30", "10000000");
    }

    private ResultActions estimate(String joinType, String birthDate, String payTermYears, String amount)
            throws Exception {
        return mvc.perform(get("/api/simulator/estimate")
                .param("joinType", joinType).param("birthDate", birthDate).param("sex", "M")
                .param("payTermYears", payTermYears).param("amount", amount));
    }

    @Test
    void 월_보험료가_예시표_역산값과_같다_18190_16580_다르면_데이터_정합성_이슈() throws Exception {
        exampleEstimate()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.standard.productCode").value("4008400"))
                .andExpect(jsonPath("$.plans.standard.monthlyPremium").value(18_190))
                .andExpect(jsonPath("$.plans.halfSurrender.productCode").value("4008300"))
                .andExpect(jsonPath("$.plans.halfSurrender.monthlyPremium").value(16_580))
                .andExpect(jsonPath("$.monthlyPremiumDiff").value(1_610));
    }

    @Test
    void 기준일은_시스템_날짜이고_보험나이_40() throws Exception {
        exampleEstimate()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basisDate").value(BUSINESS_DATE))
                .andExpect(jsonPath("$.insuranceAge").value(40))
                .andExpect(jsonPath("$.input.joinType").value("GENERAL"))
                .andExpect(jsonPath("$.input.payTermYears").value(30))
                .andExpect(jsonPath("$.input.amount").value(10_000_000));
    }

    @Test
    void 예시_조건_일치_30년납은_7개_시점_EXAMPLE_EXACT_명세_6장_값() throws Exception {
        exampleEstimate()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(7)))
                // 10년 시점 (명세 5장 예시 JSON과 같은 값)
                .andExpect(jsonPath("$.points[4].elapsedYears").value(10))
                .andExpect(jsonPath("$.points[4].paidUp").value(false))
                .andExpect(jsonPath("$.points[4].standard.paidTotal").value(2_182_800))
                .andExpect(jsonPath("$.points[4].standard.remainingTotal").value(4_365_600))
                .andExpect(jsonPath("$.points[4].standard.surrender.basis").value("EXAMPLE_EXACT"))
                .andExpect(jsonPath("$.points[4].standard.surrender.amount").value(1_792_580))
                .andExpect(jsonPath("$.points[4].standard.surrender.rate").value(82.1))
                .andExpect(jsonPath("$.points[4].standard.deathBenefit").value(10_000_000))
                .andExpect(jsonPath("$.points[4].halfSurrender.paidTotal").value(1_989_600))
                .andExpect(jsonPath("$.points[4].halfSurrender.surrender.amount").value(896_290))
                .andExpect(jsonPath("$.points[4].halfSurrender.surrender.rate").value(45.0))
                // 납입 완료 시점: 50%형도 표준형과 같은 환급금 (약관 제34조 다)
                .andExpect(jsonPath("$.points[6].elapsedYears").value(30))
                .andExpect(jsonPath("$.points[6].paidUp").value(true))
                .andExpect(jsonPath("$.points[6].standard.remainingTotal").value(0))
                .andExpect(jsonPath("$.points[6].standard.surrender.amount").value(6_680_600))
                .andExpect(jsonPath("$.points[6].standard.surrender.rate").value(102.0))
                .andExpect(jsonPath("$.points[6].halfSurrender.surrender.amount").value(6_680_600))
                .andExpect(jsonPath("$.points[6].halfSurrender.surrender.rate").value(111.9));
    }

    @Test
    void v1_필드는_null_이고_clauses는_빈_배열() throws Exception {
        exampleEstimate()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].standard.netCost").value(nullValue()))
                .andExpect(jsonPath("$.points[0].halfSurrender.netCost").value(nullValue()))
                .andExpect(jsonPath("$.points[0].betterIfSurrender").value(nullValue()))
                .andExpect(jsonPath("$.clauses").isArray())
                .andExpect(jsonPath("$.clauses").isEmpty());
    }

    @Test
    void 예시_기준_조건이_응답에_항상_실린다() throws Exception {
        exampleEstimate()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exampleCondition.sex").value("M"))
                .andExpect(jsonPath("$.exampleCondition.age").value(40))
                .andExpect(jsonPath("$.exampleCondition.amount").value(10_000_000))
                .andExpect(jsonPath("$.exampleCondition.payTermYears").value(30))
                .andExpect(jsonPath("$.exampleCondition.source").value("상품요약서 p.63 (신공시이율Ⅳ 2.05% 유지 가정)"))
                .andExpect(jsonPath("$.assumptions", hasSize(3)));
    }

    @Test
    void 삼십년납이지만_금액이_다르면_REFERENCE_RATE_금액은_null() throws Exception {
        estimate("GENERAL", "1986-10-01", "30", "20000000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[4].standard.surrender.basis").value("REFERENCE_RATE"))
                .andExpect(jsonPath("$.points[4].standard.surrender.amount").value(nullValue()))
                .andExpect(jsonPath("$.points[4].standard.surrender.rate").value(82.1));
    }

    @Test
    void 삼십년납이_아니면_NOT_AVAILABLE_시점은_1_3_5_7_10() throws Exception {
        estimate("GENERAL", "1986-10-01", "10", "10000000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(5)))
                .andExpect(jsonPath("$.points[4].elapsedYears").value(10))
                .andExpect(jsonPath("$.points[4].paidUp").value(true))
                .andExpect(jsonPath("$.points[0].standard.surrender.basis").value("NOT_AVAILABLE"))
                .andExpect(jsonPath("$.points[0].standard.surrender.amount").value(nullValue()))
                .andExpect(jsonPath("$.points[0].standard.surrender.rate").value(nullValue()));
    }

    @Test
    void 이종은_2종_상품_코드로_계산한다() throws Exception {
        estimate("SIMPLIFIED", "1986-10-01", "30", "10000000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.standard.productCode").value("4008600"))
                .andExpect(jsonPath("$.plans.halfSurrender.productCode").value("4008500"))
                // 2종 예시표도 남 40세·1,000만·30년납 (상품요약서 p.64~65 — 명세 확인 필요 #5의 답)
                .andExpect(jsonPath("$.points[4].standard.surrender.basis").value("EXAMPLE_EXACT"))
                .andExpect(jsonPath("$.points[4].standard.surrender.amount").value(1_977_000));
    }

    @Test
    void 가입금액_900만_1200만_4500만은_400_INVALID_AMOUNT() throws Exception {
        for (String amount : new String[] {"9000000", "12000000", "45000000"}) {
            estimate("GENERAL", "1986-10-01", "30", amount)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        }
    }

    @Test
    void 이종_66세_이상은_2000만_초과_400_같거나_작으면_200() throws Exception {
        // 1960-11-01생 → 2026-11-27 기준 만 66년 0월 26일 → 보험나이 66세
        estimate("SIMPLIFIED", "1960-11-01", "10", "30000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        estimate("SIMPLIFIED", "1960-11-01", "10", "20000000")
                .andExpect(status().isOk());
    }

    @Test
    void 일종_30년납_51세는_400_AGE_NOT_ALLOWED() throws Exception {
        // 1975-11-01생 → 보험나이 51세. 1종 30년납 요율은 15~50세만 존재
        estimate("GENERAL", "1975-11-01", "30", "10000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGE_NOT_ALLOWED"));
    }

    @Test
    void 지원하지_않는_납입기간은_400_PAY_TERM_NOT_SUPPORTED() throws Exception {
        // 80세납(보험나이 40세면 40년납 상당)은 v0 제외
        estimate("GENERAL", "1986-10-01", "40", "10000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAY_TERM_NOT_SUPPORTED"));
    }

    @Test
    void 같은_입력_두_번이면_같은_결과_멱등() throws Exception {
        String first = exampleEstimate().andReturn().getResponse().getContentAsString();
        String second = exampleEstimate().andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
    }
}
