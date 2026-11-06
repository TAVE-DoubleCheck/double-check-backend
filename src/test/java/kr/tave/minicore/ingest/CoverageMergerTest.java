package kr.tave.minicore.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.tave.minicore.ingest.CoverageMerger.Paragraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageMergerTest {

    private final List<ApiModels.CoverageLine> lines =
            new ApiResponseParser(new ObjectMapper()).parseCoverage(Fixtures.read("coverage_page1_real.json")).items();

    @Test
    void 납입면제_각주는_사망보험금_조건과_분리된_부가조건_문단이_된다() {
        List<Paragraph> p = CoverageMerger.merge(lines).stream()
                .filter(x -> x.insuNm().equals(Fixtures.COVERAGE_NAME_MAP.get("4008400")))
                .toList();

        assertThat(p).hasSize(2);

        assertThat(p.get(0).benefitName()).isEqualTo("사망보험금");
        assertThat(p.get(0).note()).isFalse();
        assertThat(p.get(0).text()).isEqualTo("보험기간 중 사망하였을 때");

        assertThat(p.get(1).benefitName()).isEqualTo("사망보험금");
        assertThat(p.get(1).note()).isTrue();
        assertThat(p.get(1).sourceLineNo()).isEqualTo(4);
        assertThat(p.get(1).text())
                .startsWith("보험료 납입기간 중")
                .contains("원인으로 여러 신체부위의")           // 줄바꿈 지점이 공백 1칸으로 이어짐
                .endsWith("차회 이후의 보험료 납입을 면제합니다.");
    }

    @Test
    void 섹션_머리말_주계약은_문단으로_만들지_않는다() {
        assertThat(CoverageMerger.merge(lines)).noneMatch(x -> x.benefitName().contains("주계약"));
    }
}
