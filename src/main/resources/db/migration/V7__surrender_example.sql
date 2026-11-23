-- =====================================================================
-- V7: 해약환급금 예시표 (상품요약서 공시값)
--   데이터는 seed/reference_seed.sql (product FK가 있어 product_seed 복원 뒤에 넣어야 함)
-- =====================================================================
-- 해약환급금 예시표 (상품요약서 공시값 그대로)
-- 배경: 해약환급금은 산출방법서(비공개)로 계산하므로 직접 계산하지 않는다.
--       시뮬레이터의 '이 시점에 해약하면' 금액은 이 예시표에서만 가져온다.
-- 출처: 무배당 우체국하나로OK보험 2609 상품요약서 p.63~65 (공시실 상품목록 '상품요약서')
-- 예시 기준: 남자 40세, 주계약 가입금액 1,000만 원, 종신, 30년납, 신공시이율Ⅳ 2.05% 유지 가정, 예정이율 2.50%
-- 표의 '-'(해약환급금 없음)는 0으로 저장
-- sex는 premium_rate.sex와 같은 'M'/'W', insure_term은 premium_rate.insure_term_code와 같은 '99'(종신)

CREATE TABLE surrender_example_set (
    product_code          VARCHAR(10)   PRIMARY KEY REFERENCES product (product_code),  -- 보험료 API 상품코드 (예: 4008400). 약관코드 P400084 아님
    label                 VARCHAR(60)   NOT NULL,
    sex                   CHAR(1)       NOT NULL CHECK (sex IN ('M', 'W')),
    join_age              INT           NOT NULL,
    sum_insured_won       BIGINT        NOT NULL,
    pay_term_years        INT           NOT NULL,
    insure_term_code      VARCHAR(10)   NOT NULL,      -- premium_rate.insure_term_code와 같은 값 ('99' = 종신)
    assumed_disclosed_rate NUMERIC(6,4) NOT NULL,      -- 예시에 가정된 신공시이율Ⅳ
    expected_rate         NUMERIC(6,4)  NOT NULL,      -- 예정이율 (최저해약환급금 기준)
    source_doc            VARCHAR(60)   NOT NULL,
    source_page           INT           NOT NULL
);

CREATE TABLE surrender_example (
    product_code      VARCHAR(10) NOT NULL REFERENCES surrender_example_set (product_code),
    elapsed_years     INT         NOT NULL,
    paid_premium_won  BIGINT      NOT NULL,
    min_surrender_won BIGINT      NOT NULL,   -- 최저해약환급금 (예정이율 기준, 최저 보장)
    surrender_won     BIGINT      NOT NULL,   -- 신공시이율Ⅳ 적용 해약환급금 (예시 표시 값)
    CONSTRAINT pk_surrender_example PRIMARY KEY (product_code, elapsed_years),
    CONSTRAINT ck_surrender_nonneg CHECK (elapsed_years > 0 AND paid_premium_won >= 0 AND min_surrender_won >= 0),
    -- 상품요약서 주3: 둘 중 큰 금액을 예시하므로 surrender >= min_surrender
    CONSTRAINT ck_surrender_ge_min CHECK (surrender_won >= min_surrender_won)
);

COMMENT ON TABLE surrender_example IS '공시 예시값. 수정 금지, 새 상품요약서가 나오면 새 상품코드로 추가';
