package kr.tave.minicore.contract.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static kr.tave.minicore.contract.domain.ContractStatus.ACTIVE;
import static kr.tave.minicore.contract.domain.ContractStatus.LAPSED;
import static kr.tave.minicore.contract.domain.ContractStatus.PAID_UP;
import static kr.tave.minicore.contract.domain.ContractStatus.PENDING;
import static kr.tave.minicore.contract.domain.ContractStatus.PREMIUM_WAIVED;
import static kr.tave.minicore.contract.domain.ContractStatus.TERMINATED;
import static kr.tave.minicore.contract.domain.ContractStatus.VOID;
import static org.assertj.core.api.Assertions.assertThat;

/** 계약 상태 전이 표 (정리 v2 7.1). 7 × 7 = 49가지 조합을 전부 확인한다. */
class ContractStatusTest {

    /** 문서에 적힌 허용 전이. 여기 없는 조합은 전부 금지 */
    private static final Map<ContractStatus, Set<ContractStatus>> EXPECTED = Map.of(
            PENDING, EnumSet.of(ACTIVE, VOID),
            ACTIVE, EnumSet.of(PAID_UP, LAPSED, PREMIUM_WAIVED, TERMINATED),
            PAID_UP, EnumSet.of(TERMINATED),
            PREMIUM_WAIVED, EnumSet.of(TERMINATED),
            LAPSED, EnumSet.noneOf(ContractStatus.class),
            TERMINATED, EnumSet.noneOf(ContractStatus.class),
            VOID, EnumSet.noneOf(ContractStatus.class));

    @Test
    void 모든_상태_조합이_문서의_전이표와_같다() {
        for (ContractStatus from : ContractStatus.values()) {
            for (ContractStatus to : ContractStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void 같은_상태로는_바꿀_수_없다() {
        for (ContractStatus s : ContractStatus.values()) {
            assertThat(s.canTransitionTo(s)).as(s.name()).isFalse();
        }
    }

    @Test
    void 실효된_계약은_납입면제나_종료로_바꿀_수_없다() {
        assertThat(LAPSED.canTransitionTo(PREMIUM_WAIVED)).isFalse();
        assertThat(LAPSED.canTransitionTo(TERMINATED)).isFalse();
    }

    @Test
    void 보장_중인_상태는_유지_납입완료_납입면제뿐이다() {
        assertThat(EnumSet.allOf(ContractStatus.class).stream().filter(ContractStatus::isCovered))
                .containsExactlyInAnyOrder(ACTIVE, PAID_UP, PREMIUM_WAIVED);
    }

    @Test
    void 실효_종료_불성립은_끝_상태다() {
        assertThat(EnumSet.allOf(ContractStatus.class).stream().filter(ContractStatus::isFinal))
                .containsExactlyInAnyOrder(LAPSED, TERMINATED, VOID);
    }
}
