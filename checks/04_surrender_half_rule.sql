-- =====================================================================
-- 검증 ④: 해약환급금 예시표가 약관의 50% 규칙을 지키는가
--   약관 제34조 유의사항 다 / 사업방법서 11.다
--     납입기간 중 해지: 50%형 = 표준형 예정해약환급금의 50% (예시표는 10원 단위 반올림 → 차이 10원 이하 허용)
--     납입 완료 후 해지: 50%형 = 표준형
--   같은 가입유형(1종/2종)·같은 경과연수끼리 비교. 플러스적립금은 예시에 포함되지 않으므로 무시.
--
-- 결과 0행 = 통과. 비교 쌍이 없으면 NO_PAIRS 1행.
-- =====================================================================
WITH ex AS (
    SELECT e.*, s.pay_term_years,
           CASE WHEN strpos(p.short_name, '표준') > 0 THEN '표준'
                WHEN strpos(p.short_name, '50%') > 0 THEN '50%' END AS refund_form,
           CASE WHEN strpos(p.short_name, '1종') > 0 THEN '1종'
                WHEN strpos(p.short_name, '2종') > 0 THEN '2종' END AS join_kind
      FROM surrender_example e
      JOIN surrender_example_set s ON s.product_code = e.product_code
      JOIN product p ON p.product_code = e.product_code
),
pairs AS (
    SELECT h.join_kind, h.elapsed_years, h.pay_term_years,
           st.surrender_won AS std_won, h.surrender_won AS half_won
      FROM ex h
      JOIN ex st ON st.refund_form = '표준' AND st.join_kind = h.join_kind
                AND st.elapsed_years = h.elapsed_years
     WHERE h.refund_form = '50%'
)
SELECT 'VIOLATION: 50% 규칙 불일치' AS issue, *
  FROM pairs
 WHERE (elapsed_years <  pay_term_years AND abs(std_won / 2.0 - half_won) > 10)
    OR (elapsed_years >= pay_term_years AND std_won <> half_won)
UNION ALL
SELECT 'NO_PAIRS: 비교 쌍 0개 (조인 조건 점검 필요)', NULL, NULL, NULL, NULL, NULL
 WHERE NOT EXISTS (SELECT 1 FROM pairs);
