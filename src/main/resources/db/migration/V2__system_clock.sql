-- =====================================================================
-- V2: 시스템 시계 (F3)
--   시스템 전체에 1행. 업무 날짜는 모두 이 값 기준.
--   주의: 기존 ERD의 컬럼명 current_date는 PostgreSQL 예약어(CURRENT_DATE 함수)라
--        business_date로 변경했다.
-- =====================================================================

CREATE TABLE system_clock (
    id             SMALLINT     PRIMARY KEY DEFAULT 1,
    business_date  DATE         NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_system_clock_single_row CHECK (id = 1)   -- 2번째 행 생성 불가
);

-- 초기값: 마이그레이션 실행일.
INSERT INTO system_clock (id, business_date) VALUES (1, CURRENT_DATE);

-- 시계는 앞으로만 이동. 애플리케이션 검증과 별개로 DB에서도 최후 방어선.
-- (과거로 가면 원장에 '미래에 지급된 돈'이 생겨 정합성이 깨진다)
CREATE FUNCTION fn_system_clock_forward_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'system_clock row cannot be deleted';
    END IF;
    IF NEW.business_date < OLD.business_date THEN
        RAISE EXCEPTION 'system_clock cannot move backward: % -> %',
            OLD.business_date, NEW.business_date;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_system_clock_forward_only
    BEFORE UPDATE OR DELETE ON system_clock
    FOR EACH ROW EXECUTE FUNCTION fn_system_clock_forward_only();
