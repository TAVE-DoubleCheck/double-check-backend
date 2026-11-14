-- =====================================================================
-- V4: 회원 · 계약 · 납입 영역 (BE B)
--   users / contract / contract_history / premium_bill
--
-- 원칙
--   * 계약은 가입 당시 상품 버전(product_version.id)을 계속 가리킨다. (연결 규칙 1)
--   * 월 보험료는 QuoteService 결과를 그대로 저장하고 다시 계산하지 않는다.
--   * 금액은 원 단위 정수(BIGINT). 업무 날짜(DATE)는 전부 system_clock 기준,
--     created_at 등 기술용 시각만 DB now().
--   * 계약일 = 첫 보험료 낸 날 = 보장 시작일 (약관 제25조①) → 청약 단계에선 NULL.
--   * 계약 상태 이력은 추가만 한다 (수정·삭제는 트리거로 차단).
--   * 장부(ledger, BE D)는 premium_bill.id를 가리킨다. (연결 규칙 3)
-- =====================================================================

-- ---------------------------------------------------------------------
-- users : 회원 (고객 + 직원). 직원 계정은 회원가입 없이 seed로 생성.
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                       BIGSERIAL     PRIMARY KEY,
    email                    VARCHAR(255)  NOT NULL,
    password_hash            VARCHAR(100)  NOT NULL,           -- BCrypt. 평문 저장 금지
    name                     VARCHAR(100)  NOT NULL,
    birth_date               DATE          NOT NULL,           -- 보험나이 계산 원천. 가입 시 요청값이 아니라 이 값을 쓴다
    sex                      CHAR(1)       NOT NULL,           -- premium_rate.sex와 같은 값 ('M' / 'W')
    role                     VARCHAR(10)   NOT NULL DEFAULT 'CUSTOMER',
    email_opt_in             BOOLEAN       NOT NULL DEFAULT FALSE,
    email_opt_in_changed_at  TIMESTAMPTZ,                      -- 알림 동의·철회 시각 (API v0.4 마이페이지)
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_users_sex  CHECK (sex IN ('M', 'W')),
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

-- 대소문자만 다른 이메일 중복 가입 방지
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));

-- ---------------------------------------------------------------------
-- contract : 계약서 1장. QuoteResult를 그대로 복사해 저장한다.
--   상태 머신
--     PENDING  ─첫 보험료 납부→ ACTIVE ─마지막 회차→ PAID_UP
--     PENDING  ─첫 보험료 미납→ VOID
--     ACTIVE   ─유예기간 끝까지 미납→ LAPSED
--     ACTIVE   ─장해 승인(BE C)→ PREMIUM_WAIVED
--     ACTIVE/PAID_UP/PREMIUM_WAIVED ─사망 지급(BE C)→ TERMINATED
--   허용 전이 검사는 애플리케이션(ContractStateMachine) 한 곳에서만 한다.
-- ---------------------------------------------------------------------
CREATE SEQUENCE contract_no_seq;

CREATE TABLE contract (
    id                    BIGSERIAL     PRIMARY KEY,
    -- 화면 표시용 계약번호 C-000123. 시퀀스로 채번 → 동시 가입에도 겹치지 않음
    -- (실패한 가입도 번호를 소비하므로 중간 번호가 빠질 수는 있음. 연속 번호가 필요하면 별도 채번 표 필요)
    contract_no           VARCHAR(20)   NOT NULL UNIQUE
                          DEFAULT ('C-' || lpad(nextval('contract_no_seq')::text, 6, '0')),
    user_id               BIGINT        NOT NULL REFERENCES users(id),

    -- ── 가입 시점 스냅샷 (QuoteResult 그대로, 이후 변경 금지) ──
    version_id            BIGINT        NOT NULL REFERENCES product_version(id),  -- 연결 규칙 1
    sex                   CHAR(1)       NOT NULL,
    insurance_age         SMALLINT      NOT NULL,          -- 보험나이 (약관 제23조②)
    insured_amount        BIGINT        NOT NULL,          -- 가입금액 = 사망보험금 (단순화)
    monthly_premium       BIGINT        NOT NULL,          -- 예) 165350. 다시 계산하지 않는다
    pay_term_plan_type    VARCHAR(12)   NOT NULL,          -- FIXED_YEARS / TO_AGE
    pay_term_plan_value   SMALLINT      NOT NULL,          -- FIXED_YEARS면 년수, TO_AGE면 종료 나이(80)
    pay_term_years        SMALLINT      NOT NULL,          -- 실제 납입년수. 총 회차 = pay_term_years × 12
    is_full_term_pay      BOOLEAN       NOT NULL DEFAULT FALSE,
    insure_term_code      VARCHAR(10)   NOT NULL,          -- '99' = 종신
    applied_on            DATE          NOT NULL,          -- 가입 신청일 = QuoteResult.businessDate

    -- ── 첫 보험료 납부 시 확정 ──
    contract_date         DATE,                            -- 계약일 = 첫 보험료 낸 날 = 보장 시작일
    billing_day           SMALLINT,                        -- 매달 납입일 (계약일의 일자. 31이면 그달 말일로 처리)

    -- ── 운영 ──
    auto_debit            BOOLEAN       NOT NULL DEFAULT FALSE,
    status                VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    beneficiary_name      VARCHAR(100)  NOT NULL,          -- 가입 시 필수
    beneficiary_relation  VARCHAR(10)   NOT NULL,
    lock_version          BIGINT        NOT NULL DEFAULT 0, -- JPA @Version (상태 동시 변경 차단)
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_contract_sex        CHECK (sex IN ('M', 'W')),
    CONSTRAINT ck_contract_age        CHECK (insurance_age BETWEEN 0 AND 120),
    CONSTRAINT ck_contract_amount     CHECK (insured_amount > 0),
    CONSTRAINT ck_contract_premium    CHECK (monthly_premium > 0),
    CONSTRAINT ck_contract_plan_type  CHECK (pay_term_plan_type IN ('FIXED_YEARS', 'TO_AGE')),
    CONSTRAINT ck_contract_pay_term   CHECK (pay_term_years > 0),
    CONSTRAINT ck_contract_billing_day CHECK (billing_day BETWEEN 1 AND 31),
    CONSTRAINT ck_contract_status     CHECK (status IN
        ('PENDING', 'ACTIVE', 'PAID_UP', 'PREMIUM_WAIVED', 'LAPSED', 'TERMINATED', 'VOID')),
    CONSTRAINT ck_contract_relation   CHECK (beneficiary_relation IN
        ('SPOUSE', 'CHILD', 'PARENT', 'SIBLING')),
    -- 첫 보험료를 안 낸 상태(청약·불성립)에서만 계약일·납입일이 비어 있다
    CONSTRAINT ck_contract_date_by_status CHECK (
        (status IN ('PENDING', 'VOID')) = (contract_date IS NULL)
    ),
    CONSTRAINT ck_contract_billing_day_with_date CHECK (
        (contract_date IS NULL) = (billing_day IS NULL)
    ),
    CONSTRAINT ck_contract_date_after_apply CHECK (
        contract_date IS NULL OR contract_date >= applied_on
    )
);

COMMENT ON COLUMN contract.version_id      IS '가입 당시 상품 버전. 요율·약관이 바뀌어도 이 계약은 이 버전을 계속 가리킨다';
COMMENT ON COLUMN contract.monthly_premium IS 'QuoteResult.monthlyPremium 그대로. 모든 청구서 금액의 원천';
COMMENT ON COLUMN contract.contract_date   IS '약관 제25조①: 첫 보험료를 받은 날. 청약(PENDING)·불성립(VOID)이면 NULL';

CREATE INDEX ix_contract_user ON contract (user_id);
-- 매일 작업: 오늘 납입일인 유지 계약 찾기
CREATE INDEX ix_contract_status_billing ON contract (status, billing_day);

-- ---------------------------------------------------------------------
-- contract_history : 계약 상태 변경 이력 (추가만)
--   BE C 자동 점검 1번 "사고 난 날 보장 중이었나"가 이 표로 판단한다.
--   같은 날 여러 번 바뀌면 id 순서가 곧 발생 순서.
-- ---------------------------------------------------------------------
CREATE TABLE contract_history (
    id            BIGSERIAL     PRIMARY KEY,
    contract_id   BIGINT        NOT NULL REFERENCES contract(id),
    from_status   VARCHAR(16),                          -- 계약 생성 이력이면 NULL
    to_status     VARCHAR(16)   NOT NULL,
    reason        VARCHAR(200)  NOT NULL,               -- 예: '1회차 납부', '유예기간 경과(약관 제28조)'
    actor_type    VARCHAR(10)   NOT NULL,               -- 누가 바꿨나
    actor_id      BIGINT        REFERENCES users(id),   -- 배치·시스템이면 NULL
    occurred_on   DATE          NOT NULL,               -- 업무 날짜 (system_clock)
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_contract_history_actor CHECK (actor_type IN ('CUSTOMER', 'ADMIN', 'BATCH')),
    CONSTRAINT ck_contract_history_status CHECK (
        to_status IN ('PENDING', 'ACTIVE', 'PAID_UP', 'PREMIUM_WAIVED', 'LAPSED', 'TERMINATED', 'VOID')
        AND (from_status IS NULL OR from_status IN
             ('PENDING', 'ACTIVE', 'PAID_UP', 'PREMIUM_WAIVED', 'LAPSED', 'TERMINATED', 'VOID'))
        AND (from_status IS NULL OR from_status <> to_status)
    )
);

CREATE INDEX ix_contract_history_contract ON contract_history (contract_id, occurred_on, id);

-- 이력은 추가만. 애플리케이션 규칙과 별개로 DB에서도 최후 방어선 (V2 system_clock과 같은 방식)
CREATE FUNCTION fn_contract_history_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'contract_history is append-only (% not allowed)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_contract_history_append_only
    BEFORE UPDATE OR DELETE ON contract_history
    FOR EACH ROW EXECUTE FUNCTION fn_contract_history_append_only();

-- ---------------------------------------------------------------------
-- premium_bill : 보험료 청구서 (매달 1장)
--   1회차는 가입 신청 시 생성, 2회차부터는 매일 작업 ①이 발행.
--   화면 표시: ISSUED = 납부 대기 / PAID = 납입 완료 / OVERDUE = 미납
--   유예 마감일 = 납입기일이 있는 달의 다다음 달 말일 (약관 제28조①, 안건 A-5 ②안)
-- ---------------------------------------------------------------------
CREATE TABLE premium_bill (
    id              BIGSERIAL     PRIMARY KEY,
    contract_id     BIGINT        NOT NULL REFERENCES contract(id),
    installment_no  SMALLINT      NOT NULL,             -- 회차 (1부터)
    due_date        DATE          NOT NULL,             -- 납입기일
    grace_end_date  DATE          NOT NULL,             -- 유예 마감일. 다음 날 실효
    amount          BIGINT        NOT NULL,             -- = contract.monthly_premium (발행 시 복사)
    status          VARCHAR(10)   NOT NULL DEFAULT 'ISSUED',
    paid_on         DATE,                               -- 업무 날짜 기준 납부일
    paid_channel    VARCHAR(10),                        -- ONLINE(납부하기) / AUTO_DEBIT(자동이체)
    lock_version    BIGINT        NOT NULL DEFAULT 0,   -- JPA @Version → 동시 납부 차단 (낙관적 락)
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- 같은 회차 청구서는 한 장만 → 매일 작업을 같은 날 두 번 돌려도 중복 발행 불가 (멱등)
    CONSTRAINT uq_premium_bill_installment UNIQUE (contract_id, installment_no),
    CONSTRAINT ck_premium_bill_installment CHECK (installment_no >= 1),
    CONSTRAINT ck_premium_bill_amount      CHECK (amount > 0),
    CONSTRAINT ck_premium_bill_grace       CHECK (grace_end_date >= due_date),
    CONSTRAINT ck_premium_bill_status      CHECK (status IN ('ISSUED', 'PAID', 'OVERDUE')),
    CONSTRAINT ck_premium_bill_channel     CHECK (paid_channel IN ('ONLINE', 'AUTO_DEBIT')),
    -- 납부 완료일 때만 납부일·경로가 있다
    CONSTRAINT ck_premium_bill_paid CHECK (
        (status = 'PAID') = (paid_on IS NOT NULL)
        AND (paid_on IS NULL) = (paid_channel IS NULL)
    )
);

COMMENT ON COLUMN premium_bill.grace_end_date IS '약관 제28조①: 납입기일이 속한 달의 다다음 달 말일. 이날까지 미납이면 다음 날 실효';

-- 매일 작업: 오늘 납입기일인 청구서 / 유예 마감이 지난 미납 청구서 찾기
CREATE INDEX ix_premium_bill_due   ON premium_bill (status, due_date);
CREATE INDEX ix_premium_bill_grace ON premium_bill (status, grace_end_date);
