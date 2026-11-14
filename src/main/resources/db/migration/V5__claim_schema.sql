-- =====================================================================
-- V5: 보험금 청구 · 심사 · 서류 · 지급 영역 (BE C)
--   claim / claim_history / claim_assessment
--   claim_document / claim_document_content / payout
--
-- 원칙
--   * 전제: users, contract (V4, BE B). 장부(ledger, BE D)는 payout.id를 가리킨다. (연결 규칙 2·3)
--   * 이름 규칙은 V4와 같음: 일어난 날 *_on, 정해진 날 *_date (업무 날짜, system_clock 기준),
--     기술 시각 *_at (DB now()), 동시 수정 차단 lock_version (JPA @Version)
--   * 금액은 원 단위 정수(BIGINT).
--   * 상태 전이 규칙은 애플리케이션(ClaimStatus) 한 곳에서만 검사한다. DB는 값 목록만 막는다.
--   * 이력·판정 기록은 추가만 한다 (수정·삭제는 트리거로 차단).
--   * 서류 PDF는 DB에 저장한다 (안건 C-7 잠정): Render 무료 플랜은 서버 파일이 재배포·재시작 때 사라진다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- claim : 보험금 청구 1건
--   상태 흐름
--     RECEIVED ─심사 시작→ REVIEWING ─승인→ APPROVED ─(사망) 지급 실행→ PAID
--                                     │              └(장해) 납입면제 처리→ CLOSED
--                                     └─거절(사유 필수)→ REJECTED
-- ---------------------------------------------------------------------
CREATE TABLE claim (
    id                    BIGSERIAL     PRIMARY KEY,
    contract_id           BIGINT        NOT NULL REFERENCES contract(id),
    type                  VARCHAR(20)   NOT NULL,             -- DEATH / DISABILITY
    status                VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED',
    accident_date         DATE          NOT NULL,             -- 사고일. 사망 청구는 사망일
    accident_description  TEXT,                               -- 장해 청구는 필수
    claimant_name         VARCHAR(100)  NOT NULL,             -- 청구인. 장해 = 본인, 사망 = 유족
    claimant_relation     VARCHAR(10)   NOT NULL,             -- 피보험자와의 관계. 사망은 contract.beneficiary_relation과 같은 코드
    intake_channel        VARCHAR(10)   NOT NULL,             -- CUSTOMER(고객 접수) / STAFF(직원 대리 접수)
    created_by            BIGINT        NOT NULL REFERENCES users(id),   -- 접수한 계정
    received_on           DATE          NOT NULL,             -- 접수일 (system_clock)
    decided_on            DATE,                               -- 승인·거절일 = 납입면제 기준일 (회의 B-5)
    reject_reason         VARCHAR(30),
    reject_memo           TEXT,
    lock_version          BIGINT        NOT NULL DEFAULT 0,   -- JPA @Version → 직원 두 명 동시 심사 차단
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_claim_type     CHECK (type IN ('DEATH', 'DISABILITY')),
    CONSTRAINT ck_claim_status   CHECK (status IN
        ('RECEIVED', 'REVIEWING', 'APPROVED', 'REJECTED', 'PAID', 'CLOSED')),
    -- 사망은 직원 대리 접수만, 장해는 고객 본인 접수만 (회의 B-6)
    CONSTRAINT ck_claim_channel  CHECK (
        (type = 'DEATH' AND intake_channel = 'STAFF') OR
        (type = 'DISABILITY' AND intake_channel = 'CUSTOMER')
    ),
    -- 장해 = 본인 청구, 사망 = 수익자 관계 코드 (V4 ck_contract_relation과 같은 값)
    CONSTRAINT ck_claim_relation CHECK (
        (type = 'DISABILITY' AND claimant_relation = 'SELF') OR
        (type = 'DEATH' AND claimant_relation IN ('SPOUSE', 'CHILD', 'PARENT', 'SIBLING'))
    ),
    CONSTRAINT ck_claim_description   CHECK (type <> 'DISABILITY' OR accident_description IS NOT NULL),
    CONSTRAINT ck_claim_accident_date CHECK (accident_date <= received_on),
    -- 거절이면 사유 필수, 거절이 아니면 사유 없음
    CONSTRAINT ck_claim_reject CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL)),
    CONSTRAINT ck_claim_reject_reason CHECK (reject_reason IS NULL OR reject_reason IN (
        'NOT_COVERED', 'BEFORE_CONTRACT', 'AFTER_PAY_TERM', 'BENEFICIARY_MISMATCH',
        'DUPLICATE', 'EXEMPTION_ARTICLE_5', 'DISABILITY_RATE_UNDER_50')),
    -- 심사가 끝난 청구만 결정일이 있다
    CONSTRAINT ck_claim_decided CHECK ((status IN ('RECEIVED', 'REVIEWING')) = (decided_on IS NULL))
);

COMMENT ON TABLE  claim                 IS '보험금 청구. 상태 전이 규칙은 애플리케이션(ClaimStatus) 한 곳, DB는 값 목록만 검사';
COMMENT ON COLUMN claim.decided_on      IS '승인·거절일. 장해 승인이면 이날 이후 청구서부터 납입면제 (회의 B-5)';
COMMENT ON COLUMN claim.claimant_relation IS '장해 = SELF, 사망 = 수익자 관계 코드. 자동 점검 5(수익자 일치)에서 contract와 비교';

-- 중복 청구 방지 (자동 점검 6의 DB 방어선): 거절되지 않은 같은 종류 청구는 계약당 1건.
-- 거절되면 자리가 비어 재청구할 수 있다. 직원 두 명이 동시에 같은 사망 청구를 접수해도 1건만 들어간다.
CREATE UNIQUE INDEX uq_claim_active ON claim (contract_id, type) WHERE status <> 'REJECTED';
-- 직원 청구 목록: 상태·종류 필터
CREATE INDEX ix_claim_status ON claim (status, type);

-- ---------------------------------------------------------------------
-- claim_history : 청구 상태 변경 이력 (추가만, 감사 로그)
-- ---------------------------------------------------------------------
CREATE TABLE claim_history (
    id            BIGSERIAL     PRIMARY KEY,
    claim_id      BIGINT        NOT NULL REFERENCES claim(id),
    from_status   VARCHAR(20),                                -- 접수 이력이면 NULL
    to_status     VARCHAR(20)   NOT NULL,
    actor_id      BIGINT        NOT NULL REFERENCES users(id),   -- 바꾼 사람 (로그인 계정)
    reason        TEXT,                                       -- 거절 사유, 점검 실패 내용 등
    occurred_on   DATE          NOT NULL,                     -- 업무 날짜 (system_clock)
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_claim_history_changed CHECK (from_status IS DISTINCT FROM to_status)
);

COMMENT ON TABLE claim_history IS '청구 상태 변경 이력. 추가만, 수정·삭제는 트리거로 차단. 같은 날 여러 번이면 id 순서';

CREATE INDEX ix_claim_history_claim ON claim_history (claim_id, id);

-- ---------------------------------------------------------------------
-- claim_assessment : 장해지급률 판정 기록 (추가만)
--   심사자가 약관 [별표3] 장해분류표로 판정한 합산 지급률. 가장 큰 id가 현재 값.
--   50 이상이면 자동 점검 4 통과 → 승인 시 납입면제 (약관 제4조①)
-- ---------------------------------------------------------------------
CREATE TABLE claim_assessment (
    id               BIGSERIAL     PRIMARY KEY,
    claim_id         BIGINT        NOT NULL REFERENCES claim(id),
    disability_rate  SMALLINT      NOT NULL,                  -- 합산 장해지급률(%)
    basis            TEXT          NOT NULL,                  -- 예: '별표3 8.팔 1) 한 팔의 손목 이상을 잃었을 때 (60)'
    reason           TEXT          NOT NULL,
    assessed_by      BIGINT        NOT NULL REFERENCES users(id),
    assessed_on      DATE          NOT NULL,                  -- 업무 날짜 (system_clock)
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_claim_assessment_rate CHECK (disability_rate BETWEEN 0 AND 100)
);

COMMENT ON TABLE claim_assessment IS '장해지급률 판정 기록. 추가만, 가장 큰 id가 현재 값. 장해 청구에만 쓴다(애플리케이션에서 검사)';

-- 최신 판정 조회
CREATE INDEX ix_claim_assessment_latest ON claim_assessment (claim_id, id DESC);

-- ---------------------------------------------------------------------
-- claim_document : 청구 서류 정보 (종류별 1개, 다시 올리면 교체)
--   파일 내용은 claim_document_content에 따로 둔다 → 목록 조회 때 큰 파일을 읽지 않는다.
-- ---------------------------------------------------------------------
CREATE TABLE claim_document (
    id            BIGSERIAL     PRIMARY KEY,
    claim_id      BIGINT        NOT NULL REFERENCES claim(id),
    doc_type      VARCHAR(30)   NOT NULL,
    file_name     VARCHAR(255)  NOT NULL,                     -- 올린 파일 원래 이름 (화면 표시용)
    content_type  VARCHAR(100)  NOT NULL,                     -- 지금은 PDF만
    size_bytes    BIGINT        NOT NULL,
    uploaded_by   BIGINT        NOT NULL REFERENCES users(id),   -- 고객 또는 대리 업로드 직원
    uploaded_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    is_verified   BOOLEAN       NOT NULL DEFAULT FALSE,       -- 심사자 「확인함」 (진위 확인을 대신함, 단순화)
    verified_by   BIGINT        REFERENCES users(id),
    verified_at   TIMESTAMPTZ,

    -- 종류별 1개. 같은 종류를 다시 올리면 이 행을 교체하고 is_verified를 다시 FALSE로
    CONSTRAINT uq_claim_document          UNIQUE (claim_id, doc_type),
    CONSTRAINT ck_claim_document_type     CHECK (doc_type IN
        ('CLAIM_FORM', 'DIAGNOSIS', 'DEATH_CERTIFICATE', 'ID_CARD')),
    CONSTRAINT ck_claim_document_pdf      CHECK (content_type = 'application/pdf'),
    CONSTRAINT ck_claim_document_size     CHECK (size_bytes BETWEEN 1 AND 10485760),   -- 10MB (API v0.4 7장)
    -- 확인함이면 누가·언제가 반드시 있다
    CONSTRAINT ck_claim_document_verified CHECK (
        is_verified = (verified_by IS NOT NULL AND verified_at IS NOT NULL)
    )
);

COMMENT ON TABLE claim_document IS '청구 서류 정보. 종류별 1개. 파일 내용은 claim_document_content';

-- ---------------------------------------------------------------------
-- claim_document_content : 서류 PDF 내용 (DB 저장)
-- ---------------------------------------------------------------------
CREATE TABLE claim_document_content (
    document_id  BIGINT  PRIMARY KEY REFERENCES claim_document(id) ON DELETE CASCADE,
    content      BYTEA   NOT NULL,

    CONSTRAINT ck_claim_document_content_size CHECK (octet_length(content) BETWEEN 1 AND 10485760)
);

COMMENT ON TABLE claim_document_content IS
    '서류 PDF 원본 (안건 C-7 잠정: DB 저장). Render 무료 플랜은 서버 파일이 재배포·재시작 때 사라진다';

-- ---------------------------------------------------------------------
-- payout : 지급건. 사망 청구 승인 시 1건 생성, 지급 실행 시 PAID.
--   중복 지급 방지: ① claim_id UNIQUE ② 지급 실행 시 행 잠금(SELECT ... FOR UPDATE) + PENDING 확인
--                   ③ ledger.payout_id UNIQUE (V6, BE D)
-- ---------------------------------------------------------------------
CREATE TABLE payout (
    id           BIGSERIAL     PRIMARY KEY,
    claim_id     BIGINT        NOT NULL REFERENCES claim(id),
    amount       BIGINT        NOT NULL,                      -- 승인 시점 contract.insured_amount 복사 (사망보험금 = 가입금액, 단순화)
    status       VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    approved_on  DATE          NOT NULL,                      -- 승인일 (system_clock)
    paid_on      DATE,                                        -- 지급일 (system_clock). 장부 업무 날짜와 같아야 한다
    paid_by      BIGINT        REFERENCES users(id),          -- 지급 실행한 직원
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- 청구 1건당 지급건 최대 1개 (연결 규칙 2)
    CONSTRAINT uq_payout_claim  UNIQUE (claim_id),
    CONSTRAINT ck_payout_amount CHECK (amount > 0),
    CONSTRAINT ck_payout_status CHECK (status IN ('PENDING', 'PAID')),
    -- 지급 완료일 때만 지급일·지급자가 있다
    CONSTRAINT ck_payout_paid   CHECK ((status = 'PAID') = (paid_on IS NOT NULL AND paid_by IS NOT NULL))
);

COMMENT ON COLUMN payout.amount IS '지급액. 장부(LedgerService)는 금액을 인자로 받지 않고 이 값을 읽는다 (금액 출처 한 곳)';

CREATE INDEX ix_payout_status ON payout (status);

-- ---------------------------------------------------------------------
-- 추가만 하는 기록: 수정·삭제 차단 (V2 system_clock, V4 contract_history와 같은 방식)
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_claim_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_claim_history_append_only
    BEFORE UPDATE OR DELETE ON claim_history
    FOR EACH ROW EXECUTE FUNCTION fn_claim_append_only();

CREATE TRIGGER tg_claim_assessment_append_only
    BEFORE UPDATE OR DELETE ON claim_assessment
    FOR EACH ROW EXECUTE FUNCTION fn_claim_append_only();
