-- =====================================================================
-- 검증 ⑤: 상품요약서 예시표의 보험료 = 공공 API로 적재한 요율표 보험료
--   예시표 1년차 납입보험료 ÷ 12 (월납) 를
--   premium_rate(같은 나이·성별·납입기간·보험기간) × 가입금액 ÷ 단위금액 과 비교.
--   일치하면 "공시 문서와 API 적재 데이터가 같은 값"이라는 정합성 근거가 된다.
--   2026-11-21 확인: 4종 모두 일치 (표준1종 18,190 / 50%1종 16,580 / 표준2종 20,230 / 50%2종 18,470)
--
-- 결과 0행 = 통과. 대응하는 요율 행이 없으면 NO_RATE 행.
-- =====================================================================
WITH ex AS (
    SELECT s.*, e.paid_premium_won / 12 AS example_monthly_won, e.paid_premium_won % 12 AS remainder
      FROM surrender_example_set s
      JOIN surrender_example e ON e.product_code = s.product_code AND e.elapsed_years = 1
),
matched AS (
    SELECT ex.product_code, ex.example_monthly_won, ex.remainder,
           r.premium_won * ex.sum_insured_won / r.unit_amount_won AS rate_monthly_won
      FROM ex
      JOIN product_version v ON v.product_code = ex.product_code AND v.effective_to IS NULL
      JOIN premium_rate r ON r.version_id = v.id
                         AND r.join_age = ex.join_age AND r.sex = ex.sex
                         AND r.pay_term_years = ex.pay_term_years
                         AND r.insure_term_code = ex.insure_term_code
)
SELECT 'VIOLATION: 예시표 보험료 ≠ API 요율' AS issue, product_code, example_monthly_won, rate_monthly_won, remainder
  FROM matched
 WHERE example_monthly_won <> rate_monthly_won OR remainder <> 0
UNION ALL
SELECT 'NO_RATE: 예시 조건에 맞는 요율 행 없음', ex.product_code, ex.example_monthly_won, NULL, NULL
  FROM ex
 WHERE NOT EXISTS (SELECT 1 FROM matched m WHERE m.product_code = ex.product_code);
