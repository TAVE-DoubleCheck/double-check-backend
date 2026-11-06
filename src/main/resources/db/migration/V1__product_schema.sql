-- =====================================================================
-- V1: 상품 데이터 영역 (F1 상품 데이터 적재·조회)
--   product / product_version / premium_rate / coverage
--   ingest_log / raw_api_response
--
-- 원칙
--   * 서비스는 공공 API를 직접 호출하지 않고 이 테이블만 조회한다.
--   * 적재는 upsert(ON CONFLICT)로 수행 → 재실행해도 행이 늘지 않는다.
--   * 금액은 원 단위 정수(BIGINT). 소수 연산 없음.
--   * created_at 등 기술용 타임스탬프는 DB now() 사용.
--     업무 날짜(유효기간 판단, 계약일 등)는 반드시 system_clock 기준.
-- =====================================================================

-- 기간 겹침 방지(EXCLUDE 제약)에 필요. postgres 공식 이미지에 기본 포함.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------
-- product : 상품 마스터 (하나로OK보험 8종)
--   약관 API·보험료 API의 축약 상품명(GDS_NM)이 서로 일치하고,
--   보장 API는 정식 상품명(INSU_NM)만 있어 full_name으로 수작업 매핑.
-- ---------------------------------------------------------------------
CREATE TABLE product (
    product_code   VARCHAR(10)  PRIMARY KEY,           -- 보험료 API GDS_CD_S10 (예: 4008400)
    short_name     VARCHAR(100) NOT NULL UNIQUE,       -- GDS_NM (예: (무)하나로표준1종종신)
    full_name      VARCHAR(200) UNIQUE,                -- 보장 API INSU_NM. 비종신 4종은 확인 필요 → NULL 허용
    terms_code     VARCHAR(10)  NOT NULL UNIQUE,       -- 예: P400084. 'P'+상품코드 앞 6자리 규칙은 적재 코드에서 검증
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  product            IS '상품 마스터. 세 API에 공통 코드가 없어 이름·규칙으로 매핑';
COMMENT ON COLUMN product.full_name  IS '보장 API 정식 상품명(INSU_NM). 확인 전이면 NULL';

-- ---------------------------------------------------------------------
-- product_version : 적용기간별 상품 버전 (약관 API APLCN_BGNG_YMD ~ APLCN_END_YMD)
--   계약은 이 버전 id를 스냅샷으로 저장 → 요율이 바뀌어도 기존 계약 불변.
-- ---------------------------------------------------------------------
CREATE TABLE product_version (
    id              BIGSERIAL    PRIMARY KEY,
    product_code    VARCHAR(10)  NOT NULL REFERENCES product(product_code),
    effective_from  DATE         NOT NULL,             -- APLCN_BGNG_YMD
    effective_to    DATE,                              -- APLCN_END_YMD. '99991231'이면 NULL(판매 중)
    terms_url       VARCHAR(500),                      -- STPL_URL_ADDR (버전마다 약관이 다를 수 있어 버전에 보관)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_product_version UNIQUE (product_code, effective_from),       -- upsert 기준 키
    CONSTRAINT ck_product_version_range
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    -- 같은 상품의 적용기간이 겹치면 거부 → "오늘 유효한 버전"이 항상 최대 1개
    CONSTRAINT ex_product_version_no_overlap EXCLUDE USING gist (
        product_code WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&
    )
);

COMMENT ON TABLE product_version IS '상품 적용기간별 버전. 같은 상품의 기간 겹침은 EXCLUDE 제약으로 차단';

-- ---------------------------------------------------------------------
-- premium_rate : 요율표 (1회 보험료계산 API)
--   월 보험료 = premium_won × 가입금액 ÷ unit_amount_won
--   예) 33,070원 × 5,000만원 ÷ 1,000만원 = 165,350원
-- ---------------------------------------------------------------------
CREATE TABLE premium_rate (
    version_id         BIGINT       NOT NULL REFERENCES product_version(id),
    join_age           SMALLINT     NOT NULL,          -- JOIN_AGE_S4 (요청 파라미터)
    sex                CHAR(1)      NOT NULL,          -- SEX_S1: 'M' / 'W'
    pay_term_years     SMALLINT     NOT NULL,          -- PYMT_TRM_S10. '전기납'은 보험기간 값으로 치환
    is_full_term_pay   BOOLEAN      NOT NULL DEFAULT FALSE, -- 원본이 '전기납'이었는지 (치환 전 정보 보존)
    insure_term_code   VARCHAR(10)  NOT NULL,          -- INSU_TRM_S10 원본 그대로 ('99','60','70')
    premium_won        BIGINT       NOT NULL,          -- PRUM_S16 '33070원' → 33070
    unit_amount_won    BIGINT       NOT NULL,          -- PRUM_KEY_UNIT_AMT_S20 '1000만원' → 10000000
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_premium_rate
        PRIMARY KEY (version_id, join_age, sex, pay_term_years, insure_term_code),
    CONSTRAINT ck_premium_rate_sex       CHECK (sex IN ('M', 'W')),
    CONSTRAINT ck_premium_rate_age       CHECK (join_age BETWEEN 0 AND 120),
    CONSTRAINT ck_premium_rate_pay_term  CHECK (pay_term_years > 0),
    CONSTRAINT ck_premium_rate_premium   CHECK (premium_won > 0),
    CONSTRAINT ck_premium_rate_unit      CHECK (unit_amount_won > 0)
);

COMMENT ON COLUMN premium_rate.insure_term_code IS
    '보험기간 원본 코드. 99=종신. 60·70이 세 만기인지 년 만기인지 확인 필요 → 해석 전까지 원본 보관';

-- 가입 화면: 상품별 선택 가능한 납입기간·보험기간 DISTINCT 조회용
CREATE INDEX ix_premium_rate_lookup ON premium_rate (version_id, sex, join_age);

-- ---------------------------------------------------------------------
-- coverage : 보장 항목 (보장내용 API)
--   GIVE_CN_SNTC_CN이 줄 단위로 쪼개져 오므로 급부명 기준으로 이어붙여 저장.
-- ---------------------------------------------------------------------
CREATE TABLE coverage (
    id              BIGSERIAL    PRIMARY KEY,
    version_id      BIGINT       NOT NULL REFERENCES product_version(id),
    benefit_name    VARCHAR(100) NOT NULL,             -- GIVE_BNFI_NM (예: 사망보험금, 납입면제)
    condition_text  TEXT         NOT NULL,             -- GIVE_CN_SNTC_CN 병합 결과
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_coverage UNIQUE (version_id, benefit_name)  -- upsert 기준 키
);

-- ---------------------------------------------------------------------
-- ingest_log : 적재 실행 기록
-- ---------------------------------------------------------------------
CREATE TABLE ingest_log (
    id            BIGSERIAL    PRIMARY KEY,
    api           VARCHAR(20)  NOT NULL,               -- TERMS / COVERAGE / PREMIUM
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    call_count    INT          NOT NULL DEFAULT 0,
    row_count     INT          NOT NULL DEFAULT 0,
    status        VARCHAR(10)  NOT NULL DEFAULT 'RUNNING',
    message       TEXT,

    CONSTRAINT ck_ingest_log_api    CHECK (api IN ('TERMS', 'COVERAGE', 'PREMIUM')),
    CONSTRAINT ck_ingest_log_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_ingest_log_finish CHECK (
        (status = 'RUNNING' AND finished_at IS NULL) OR
        (status <> 'RUNNING' AND finished_at IS NOT NULL)
    )
);

-- ---------------------------------------------------------------------
-- raw_api_response : API 원본 응답 보관 (기존 ERD에 없던 추가 테이블)
--   전처리 규칙이 바뀌어도 재호출 없이 재가공 가능 + 원본 변경 추적 근거.
-- ---------------------------------------------------------------------
CREATE TABLE raw_api_response (
    id              BIGSERIAL    PRIMARY KEY,
    ingest_log_id   BIGINT       NOT NULL REFERENCES ingest_log(id),
    api             VARCHAR(20)  NOT NULL,
    request_params  JSONB        NOT NULL,             -- 예: {"GDS_NM":"하나로","JOIN_AGE_S4":30,"SEX_S1":"M"} (인증키 제외)
    response_body   JSONB        NOT NULL,
    fetched_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_raw_api_response_log ON raw_api_response (ingest_log_id);
