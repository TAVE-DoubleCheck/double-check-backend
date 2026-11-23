-- =====================================================================
-- V6: 공시 이율 (보험금 지급 지연이자·해약환급금 미청구 이자 계산용)
--   데이터는 seed/reference_seed.sql (매월 공시값이 추가되므로 마이그레이션에 넣지 않음)
-- =====================================================================
-- 공시 이율 원본 + 환급금대출이율 스프레드 규칙
-- 배경: 보험금 지급 지연이자(약관 별표4)는 "환급금대출이율 + 기간별 가산이율"로 계산한다.
--       우체국보험 공시실 기준, 신공시이율Ⅳ 연동상품의 환급금대출이율 = 신공시이율Ⅳ + 1.0%.
--       공시된 원본 값(신공시이율)을 그대로 저장하고, 대출이율은 스프레드 규칙으로 도출한다.

-- btree_gist 확장은 V1에서 생성됨

-- 1) 공시 이율 원본 (월 단위, 매월 1일~말일 확정 적용)
CREATE TABLE disclosed_rate (
    rate_type        VARCHAR(40)   NOT NULL,   -- 예: NEW_DISCLOSED_IV_PROTECTION (신공시이율Ⅳ·보장)
    effective_month  DATE          NOT NULL,   -- 적용 월의 1일
    rate             NUMERIC(6, 4) NOT NULL,   -- 0.0205 = 2.05%
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_disclosed_rate PRIMARY KEY (rate_type, effective_month),
    CONSTRAINT ck_disclosed_rate_first_day CHECK (EXTRACT(DAY FROM effective_month) = 1),
    CONSTRAINT ck_disclosed_rate_range CHECK (rate >= 0 AND rate < 1)
);

COMMENT ON TABLE disclosed_rate IS '우체국보험 공시실 적용이율(월별 원본). 수정 대신 새 월 행 추가';

-- 2) 환급금대출이율 = 기준 공시이율 + 스프레드 (적용 기간이 겹치면 DB가 거부)
CREATE TABLE loan_rate_spread (
    id              BIGSERIAL     PRIMARY KEY,
    base_rate_type  VARCHAR(40)   NOT NULL,
    spread          NUMERIC(6, 4) NOT NULL,    -- 0.0100 = +1.0%
    effective_from  DATE          NOT NULL,
    effective_to    DATE,                      -- NULL = 현재까지
    source_note     VARCHAR(200)  NOT NULL,
    CONSTRAINT ck_spread_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ex_spread_no_overlap EXCLUDE USING gist (
        base_rate_type WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&
    )
);
