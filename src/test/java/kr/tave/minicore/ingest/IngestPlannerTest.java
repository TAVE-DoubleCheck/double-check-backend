package kr.tave.minicore.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.tave.minicore.ingest.ApiModels.CoverageLine;
import kr.tave.minicore.ingest.ApiModels.PremiumItem;
import kr.tave.minicore.ingest.ApiModels.TermsItem;
import kr.tave.minicore.ingest.IngestPlanner.IngestValidationException;
import kr.tave.minicore.ingest.IngestPlanner.RateRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 보험료 픽스처는 합성 데이터(구조만 실제). 4008400 / 10년 / 99 = 33,070원만 실제 관찰값. */
class IngestPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private final ApiResponseParser parser = new ApiResponseParser(new ObjectMapper());
    private final List<TermsItem> terms = parser.parseTerms(Fixtures.read("terms_hanaro_real.json")).items();
    private final List<PremiumItem> premiums = parser.parsePremium(Fixtures.read("premium_hanaro_30M_SYNTHETIC.json")).items();
    private final List<CoverageLine> coverage = parser.parseCoverage(Fixtures.read("coverage_page1_real.json")).items();

    private IngestPlanner.WritePlan plan(List<TermsItem> t, List<PremiumItem> p) {
        return IngestPlanner.plan(t, p, coverage, Fixtures.COVERAGE_NAME_MAP, TODAY);
    }

    @Test
    void 정상_계획() {
        var plan = plan(terms, premiums);

        assertThat(plan.products()).hasSize(8);
        assertThat(plan.versions()).hasSize(8);
        assertThat(plan.rates()).hasSize(16);
        assertThat(plan.report().excludedPremiumProducts()).containsExactly("(무)하나로OK(4102901)");
        assertThat(plan.products()).filteredOn(p -> p.fullName() != null).hasSize(4);
    }

    @Test
    void 약관코드는_URL_값을_쓴다_기존_P_앞6자리_규칙은_실제와_다름() {
        var p = plan(terms, premiums).products().stream()
                .filter(x -> x.productCode().equals("4008300")).findFirst().orElseThrow();
        assertThat(p.termsCode()).isEqualTo("P400083");   // 규칙대로면 P400830
    }

    @Test
    void 월보험료_예시_165350원() {
        RateRow r = plan(terms, premiums).rates().stream()
                .filter(x -> x.productCode().equals("4008400") && x.payTermYears() == 10)
                .findFirst().orElseThrow();
        assertThat(r.premiumWon() * 50_000_000L / r.unitAmountWon()).isEqualTo(165_350L);
    }

    @Test
    void 범위_밖_상품은_같은_이름에_코드가_여러_개여도_제외만_한다() {
        // 실제 적재(2026-10-27)에서 (무)하나로OK가 4102901, 4103001 등 여러 코드로 옴
        List<PremiumItem> withSecondOk = new ArrayList<>(premiums);
        PremiumItem ok = premiums.stream().filter(p -> p.gdsCd().equals("4102901")).findFirst().orElseThrow();
        withSecondOk.add(new PremiumItem(ok.joinAge(), ok.sex(), ok.prum(), ok.pymtTrm(), ok.insuTrm(), ok.unitAmt(),
                ok.ctrtDvsnNm(), ok.ctrtTypNm(), "4103001", ok.gdsNm()));

        var plan = plan(terms, withSecondOk);

        assertThat(plan.rates()).hasSize(16);
        assertThat(plan.report().excludedPremiumProducts())
                .containsExactly("(무)하나로OK(4102901)", "(무)하나로OK(4103001)");
    }

    @Test
    void 범위_안_상품이_같은_이름에_다른_코드면_실패() {
        List<PremiumItem> conflict = new ArrayList<>(premiums);
        PremiumItem p = premiums.get(0);
        conflict.add(new PremiumItem(p.joinAge(), p.sex(), p.prum(), "30년", p.insuTrm(), p.unitAmt(),
                p.ctrtDvsnNm(), p.ctrtTypNm(), "9999999", p.gdsNm()));
        assertThatThrownBy(() -> plan(terms, conflict)).isInstanceOf(IngestValidationException.class)
                .hasMessageContaining("다른 상품코드");
    }

    @Test
    void 같은_요율_키가_두_번_오면_덮어쓰지_않고_실패() {
        List<PremiumItem> dup = new ArrayList<>(premiums);
        dup.add(premiums.get(2));
        assertThatThrownBy(() -> plan(terms, dup)).isInstanceOf(IngestValidationException.class)
                .hasMessageContaining("중복");
    }

    @Test
    void 약관_상품의_보험료가_없으면_실패() {
        List<PremiumItem> missing = premiums.stream().filter(p -> !p.gdsCd().equals("4009000")).toList();
        assertThatThrownBy(() -> plan(terms, missing)).isInstanceOf(IngestValidationException.class)
                .hasMessageContaining("(무)하나로표준2종");
    }

    @Test
    void 기준일에_유효한_버전이_없으면_실패() {
        assertThatThrownBy(() -> IngestPlanner.plan(terms, premiums, coverage, Fixtures.COVERAGE_NAME_MAP,
                LocalDate.of(2026, 9, 1))).isInstanceOf(IngestValidationException.class);
    }

    @Test
    void 주계약이_아닌_행은_건너뛰고_센다() {
        List<PremiumItem> withRider = new ArrayList<>(premiums);
        PremiumItem s = premiums.get(0);
        withRider.add(new PremiumItem(s.joinAge(), s.sex(), "100원", s.pymtTrm(), s.insuTrm(), s.unitAmt(),
                "특약", "x", s.gdsCd(), s.gdsNm()));
        var plan = plan(terms, withRider);
        assertThat(plan.rates()).hasSize(16);
        assertThat(plan.report().skippedNonMainContractRows()).isEqualTo(1);
    }
}
