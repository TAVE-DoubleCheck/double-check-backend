-- V9: ledger. 납부/지급 완료 처리 후 같은 트랜잭션에서 INSERT한다.
-- LedgerService 구현은 별도 작업. 중복 요청은 UNIQUE를 사용해 기존 기록을 조회한다.
CREATE TABLE ledger (
    id               BIGSERIAL    PRIMARY KEY,
    contract_id      BIGINT       NOT NULL REFERENCES contract(id),
    type             VARCHAR(20)  NOT NULL,
    amount           BIGINT       NOT NULL,
    occurred_on      DATE         NOT NULL,
    premium_bill_id  BIGINT       REFERENCES premium_bill(id),
    payout_id        BIGINT       REFERENCES payout(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_source_pair CHECK (
        (type = 'PREMIUM_IN' AND premium_bill_id IS NOT NULL AND payout_id IS NULL)
        OR (type = 'CLAIM_OUT' AND payout_id IS NOT NULL AND premium_bill_id IS NULL)
    ),
    CONSTRAINT uq_ledger_premium_bill UNIQUE (premium_bill_id),
    CONSTRAINT uq_ledger_payout UNIQUE (payout_id)
);

CREATE INDEX ix_ledger_contract_date ON ledger (contract_id, occurred_on, id);
CREATE INDEX ix_ledger_date ON ledger (occurred_on, id);

COMMENT ON TABLE ledger IS '보험료 입금과 보험금 출금 원본 기록. 추가만 가능. 잔액은 합계로 계산';
COMMENT ON COLUMN ledger.occurred_on IS 'premium_bill.paid_on 또는 payout.paid_on과 같은 업무 날짜';

CREATE FUNCTION fn_ledger_validate_source() RETURNS trigger AS $$
DECLARE
    source_contract BIGINT;
    source_amount   BIGINT;
    source_date     DATE;
    source_status   TEXT;
BEGIN
    IF NEW.type = 'PREMIUM_IN' AND NEW.premium_bill_id IS NOT NULL AND NEW.payout_id IS NULL THEN
        SELECT b.contract_id, b.amount, b.paid_on, b.status
          INTO source_contract, source_amount, source_date, source_status
          FROM premium_bill b WHERE b.id = NEW.premium_bill_id FOR SHARE;
    ELSIF NEW.type = 'CLAIM_OUT' AND NEW.payout_id IS NOT NULL AND NEW.premium_bill_id IS NULL THEN
        SELECT c.contract_id, p.amount, p.paid_on, p.status
          INTO source_contract, source_amount, source_date, source_status
          FROM payout p JOIN claim c ON c.id = p.claim_id
         WHERE p.id = NEW.payout_id FOR SHARE OF p, c;
    ELSE
        -- NULL과 잘못된 type을 포함한 참조 조합은 테이블 CHECK/NOT NULL에서 거부한다.
        RETURN NEW;
    END IF;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'ledger source does not exist'
            USING ERRCODE = '23503';
    END IF;

    -- 행 잠금을 트랜잭션 종료까지 유지하여 검사 중 원본 변경을 막는다.
    IF source_status IS DISTINCT FROM 'PAID' OR source_date IS NULL
       OR NEW.contract_id IS DISTINCT FROM source_contract
       OR NEW.amount IS DISTINCT FROM source_amount
       OR NEW.occurred_on IS DISTINCT FROM source_date THEN
        RAISE EXCEPTION 'ledger source mismatch: PAID status, contract, amount and paid_on must match'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_ledger_source_match';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_ledger_validate_source
    BEFORE INSERT ON ledger
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_validate_source();

CREATE FUNCTION fn_ledger_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only (% not allowed)', TG_OP
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_ledger_append_only
    BEFORE UPDATE OR DELETE ON ledger
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_append_only();

-- ledger 저장 후 원본을 바꿔 일치 규칙을 우회하는 것도 차단한다.
CREATE FUNCTION fn_ledger_protect_recorded_source() RETURNS trigger AS $$
BEGIN
    IF TG_TABLE_NAME = 'premium_bill' THEN
        IF (NEW.contract_id, NEW.amount, NEW.paid_on, NEW.status)
           IS DISTINCT FROM (OLD.contract_id, OLD.amount, OLD.paid_on, OLD.status)
           AND EXISTS (SELECT 1 FROM ledger WHERE premium_bill_id = OLD.id) THEN
            RAISE EXCEPTION 'recorded ledger source cannot change' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'payout' THEN
        IF (NEW.claim_id, NEW.amount, NEW.paid_on, NEW.status)
           IS DISTINCT FROM (OLD.claim_id, OLD.amount, OLD.paid_on, OLD.status)
           AND EXISTS (SELECT 1 FROM ledger WHERE payout_id = OLD.id) THEN
            RAISE EXCEPTION 'recorded ledger source cannot change' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'claim' THEN
        IF NEW.contract_id IS DISTINCT FROM OLD.contract_id
           AND EXISTS (SELECT 1 FROM ledger l JOIN payout p ON p.id = l.payout_id WHERE p.claim_id = OLD.id) THEN
            RAISE EXCEPTION 'recorded ledger source cannot change' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_ledger_protect_bill
    BEFORE UPDATE ON premium_bill
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_protect_recorded_source();
CREATE TRIGGER tg_ledger_protect_payout
    BEFORE UPDATE ON payout
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_protect_recorded_source();
CREATE TRIGGER tg_ledger_protect_claim_contract
    BEFORE UPDATE ON claim
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_protect_recorded_source();

-- 테스트용 소유자는 TRUNCATE 가능. 운영에서는 소유자와 별도의 실행용 계정을 사용한다.
-- 실행용 계정의 권한은 환경별로 설정하고 TRUNCATE는 허용하지 않는다.
REVOKE UPDATE, DELETE, TRUNCATE ON ledger FROM PUBLIC;
