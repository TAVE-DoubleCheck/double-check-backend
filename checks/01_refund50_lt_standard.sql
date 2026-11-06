-- =====================================================================
-- 검증 ①: 해약환급금50%형 보험료 < 표준형 보험료
--   같은 가입유형(1종/2종), 종신 여부, 나이, 성별, 납입기간, 보험기간끼리 비교.
--   단위 가입금액이 다를 수 있으므로 premium_won / unit_amount_won 으로 정규화.
--
-- 결과 0행 = 통과.
--   * 위반 행: 50%형이 표준형보다 싸지 않은 조합 (고치지 말고 보고 → 약관 확인 대상)
--   * 비교 쌍이 하나도 없으면 'NO_PAIRS' 행 1개를 반환한다 (조인 조건 오류 방지)
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
    SELECT c50.short_name AS product_50,
           cst.short_name AS product_std,
           r50.join_age, r50.sex, r50.pay_term_years, r50.insure_term_code,
           r50.premium_won::numeric / r50.unit_amount_won AS unit_premium_50,
           rst.premium_won::numeric / rst.unit_amount_won AS unit_premium_std
      FROM product_class c50
      JOIN premium_rate r50 ON r50.version_id = c50.version_id
      JOIN product_class cst
        ON cst.refund_form = '표준'
       AND cst.join_kind = c50.join_kind
       AND cst.is_whole_life = c50.is_whole_life
      JOIN premium_rate rst
        ON rst.version_id = cst.version_id
       AND rst.join_age = r50.join_age
       AND rst.sex = r50.sex
       AND rst.pay_term_years = r50.pay_term_years
       AND rst.insure_term_code = r50.insure_term_code
     WHERE c50.refund_form = '50%'
)
SELECT 'VIOLATION: 50%형 >= 표준형' AS issue, *
  FROM pairs
 WHERE unit_premium_50 >= unit_premium_std
UNION ALL
SELECT 'NO_PAIRS: 비교 쌍 0개 (조인 조건 점검 필요)',
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
 WHERE NOT EXISTS (SELECT 1 FROM pairs);
