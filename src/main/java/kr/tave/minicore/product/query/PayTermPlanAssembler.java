package kr.tave.minicore.product.query;

import kr.tave.minicore.product.api.ProductDetailResponse;
import kr.tave.minicore.product.repository.PayAgeView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 요율표의 (성별, 가입나이, 납입년수) 튜플에서 납입 플랜을 유도한다. 하드코딩 없음:
 *  - 납입년수 N이 여러 나이에서 등장 → 고정 납입 플랜 FIXED_YEARS(N)  (현재 데이터: 10/15/20/30)
 *  - N이 한 나이에서만 등장하는 one-off 값들은 "나이 + N"이 전부 같은 값일 때만
 *    TO_AGE(그 값) 플랜 1개로 묶는다 (현재 데이터: 80. "80세납"은 원본 표기가 아니라 이 관계로 유도한 라벨)
 *  - 안전장치: "나이 + N"이 유일하지 않으면 묶지 않고 각각 FIXED_YEARS로 반환
 *  - 겹침(N = toAge − 나이인 표준 납입년수 행)은 양쪽 플랜 모두에 포함
 * 플랜별 나이 범위는 요율표 행 존재 여부에서 그대로 집계한다 (성별로 다를 수 있음 — 2종 남 66세 상한).
 */
final class PayTermPlanAssembler {

    private PayTermPlanAssembler() {
    }

    static List<ProductDetailResponse.PayTermPlanDto> assemble(List<PayAgeView> tuples) {
        // 납입년수 → 등장하는 나이 집합 (성별 무관)
        Map<Short, Set<Short>> agesByPay = new TreeMap<>();
        for (PayAgeView t : tuples) {
            agesByPay.computeIfAbsent(t.getPayTermYears(), k -> new TreeSet<>()).add(t.getJoinAge());
        }

        List<Short> fixed = new ArrayList<>();
        List<Short> oneOff = new ArrayList<>();
        agesByPay.forEach((pay, ages) -> (ages.size() > 1 ? fixed : oneOff).add(pay));

        List<ProductDetailResponse.PayTermPlanDto> plans = new ArrayList<>();
        for (short pay : fixed) {
            plans.add(fixedPlan(pay, tuples));
        }

        Set<Integer> toAges = oneOff.stream()
                .map(pay -> pay + agesByPay.get(pay).iterator().next())
                .collect(Collectors.toSet());
        if (oneOff.isEmpty()) {
            return plans;
        }
        if (toAges.size() == 1) {
            int toAge = toAges.iterator().next();
            plans.add(new ProductDetailResponse.PayTermPlanDto("TO_AGE", null, toAge,
                    rangesBySex(tuples, t -> t.getJoinAge() + t.getPayTermYears() == toAge)));
        } else {
            // 안전장치: 만기 나이가 하나로 수렴하지 않으면 해석하지 않고 그대로 노출
            for (short pay : oneOff) {
                plans.add(fixedPlan(pay, tuples));
            }
        }
        return plans;
    }

    private static ProductDetailResponse.PayTermPlanDto fixedPlan(short pay, List<PayAgeView> tuples) {
        return new ProductDetailResponse.PayTermPlanDto("FIXED_YEARS", (int) pay, null,
                rangesBySex(tuples, t -> t.getPayTermYears() == pay));
    }

    private static Map<String, ProductDetailResponse.AgeRange> rangesBySex(
            List<PayAgeView> tuples, Predicate<PayAgeView> inPlan) {
        Map<String, ProductDetailResponse.AgeRange> ranges = new LinkedHashMap<>();
        for (char sex : new char[]{'M', 'W'}) {
            var ages = tuples.stream()
                    .filter(t -> t.getSex() == sex)
                    .filter(inPlan)
                    .map(PayAgeView::getJoinAge)
                    .toList();
            if (!ages.isEmpty()) {
                ranges.put(String.valueOf(sex), new ProductDetailResponse.AgeRange(
                        ages.stream().min(Short::compare).orElseThrow(),
                        ages.stream().max(Short::compare).orElseThrow()));
            }
        }
        return ranges;
    }
}
