-- =====================================================================
-- 검증 ②: 2종(간편가입) 보험료 > 1종(일반가입) 보험료
--   같은 형(표준/50%), 종신 여부, 나이, 성별, 납입기간, 보험기간끼리 비교.
--
-- 결과 0행 = 통과.
--   * 위반 행: 간편가입이 일반가입보다 비싸지 않은 조합 (고치지 말고 보고)
--   * 비교 쌍이 하나도 없으면 'NO_PAIRS' 행 1개를 반환한다
-- 분류 규칙 정의: checks/00_product_class.sql (바꾸면 여기 CTE도 같이 변경)
-- =====================================================================
WITH product_class AS (
    SELECT v.id AS version_id,
           p.product_code,
           p.short_name,
           CASE WHEN strpos(p.short_name, '표준') > 0 THEN '표준'
                WHEN strpos(p.short_name, '50%') > 0 THEN '50%'
           END AS refund_form,
           CASE WHEN strpos(p.short_name, '1종') > 0 THEN '1종'
                WHEN strpos(p.short_name, '2종') > 0 THEN '2종'
           END AS join_kind,
           strpos(p.short_name, '종신') > 0 AS is_whole_life
      FROM product p
      JOIN product_version v ON v.product_code = p.product_code
),
pairs AS (
    SELECT c2.short_name AS product_simplified,
           c1.short_name AS product_general,
           r2.join_age, r2.sex, r2.pay_term_years, r2.insure_term_code,
           r2.premium_won::numeric / r2.unit_amount_won AS unit_premium_simplified,
           r1.premium_won::numeric / r1.unit_amount_won AS unit_premium_general
      FROM product_class c2
      JOIN premium_rate r2 ON r2.version_id = c2.version_id
      JOIN product_class c1
        ON c1.join_kind = '1종'
       AND c1.refund_form = c2.refund_form
       AND c1.is_whole_life = c2.is_whole_life
      JOIN premium_rate r1
        ON r1.version_id = c1.version_id
       AND r1.join_age = r2.join_age
       AND r1.sex = r2.sex
       AND r1.pay_term_years = r2.pay_term_years
       AND r1.insure_term_code = r2.insure_term_code
     WHERE c2.join_kind = '2종'
)
SELECT 'VIOLATION: 2종(간편) <= 1종(일반)' AS issue, *
  FROM pairs
 WHERE unit_premium_simplified <= unit_premium_general
UNION ALL
SELECT 'NO_PAIRS: 비교 쌍 0개 (조인 조건 점검 필요)',
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
 WHERE NOT EXISTS (SELECT 1 FROM pairs);
