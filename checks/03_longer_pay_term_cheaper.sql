-- =====================================================================
-- 검증 ③: 납입기간이 길수록 월 보험료 감소
--   같은 상품(버전), 나이, 성별, 보험기간에서 납입기간만 다른 모든 쌍을 비교.
--   납입기간이 더 길면 1회(월) 보험료는 더 싸야 정상 (총액을 더 오래 나눠 내므로).
--
-- 결과 0행 = 통과.
--   * 위반 행: 납입기간이 긴 쪽이 싸지 않은 조합 (고치지 말고 보고)
--   * 비교 쌍이 하나도 없으면 'NO_PAIRS' 행 1개를 반환한다
-- 분류 규칙 정의: checks/00_product_class.sql (여기서는 상품명 표시에만 사용)
-- =====================================================================
WITH product_class AS (
    SELECT v.id AS version_id,
           p.product_code,
           p.short_name
      FROM product p
      JOIN product_version v ON v.product_code = p.product_code
),
pairs AS (
    SELECT cls.short_name,
           a.join_age, a.sex, a.insure_term_code,
           a.pay_term_years AS pay_term_short,
           b.pay_term_years AS pay_term_long,
           a.premium_won::numeric / a.unit_amount_won AS unit_premium_short_pay,
           b.premium_won::numeric / b.unit_amount_won AS unit_premium_long_pay
      FROM premium_rate a
      JOIN premium_rate b
        ON b.version_id = a.version_id
       AND b.join_age = a.join_age
       AND b.sex = a.sex
       AND b.insure_term_code = a.insure_term_code
       AND b.pay_term_years > a.pay_term_years
      JOIN product_class cls ON cls.version_id = a.version_id
)
SELECT 'VIOLATION: 납입기간 긴 쪽이 싸지 않음' AS issue, *
  FROM pairs
 WHERE unit_premium_long_pay >= unit_premium_short_pay
UNION ALL
SELECT 'NO_PAIRS: 비교 쌍 0개 (조인 조건 점검 필요)',
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
 WHERE NOT EXISTS (SELECT 1 FROM pairs);
