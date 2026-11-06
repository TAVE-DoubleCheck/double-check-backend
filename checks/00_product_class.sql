-- =====================================================================
-- 상품 분류 규칙 (상식 검증 01~03이 공통으로 쓰는 매핑의 "원본 정의")
--
-- product 테이블에는 형/종 구분 컬럼이 없어 short_name(GDS_NM) 문자열로 분류한다.
-- 규칙을 바꿀 때는 이 파일과 01~03의 product_class CTE를 함께 바꿀 것.
-- (DA가 읽기 전용 계정으로 그대로 돌릴 수 있도록 뷰 대신 SELECT로 둔다)
--
--   refund_form   : '표준' = 표준형 / '50%' = 해약환급금50%지급형(보험료가 싸야 정상)
--   join_kind     : '1종' = 일반가입 / '2종' = 간편가입(심사 느슨, 보험료가 비싸야 정상)
--   is_whole_life : 이름에 '종신' 포함 여부 (비종신 4종은 90·100세 만기)
-- =====================================================================
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
 ORDER BY p.product_code;
