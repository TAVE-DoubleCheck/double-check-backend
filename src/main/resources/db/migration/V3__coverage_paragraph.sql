-- =====================================================================
-- V3: coverage를 "급부 1건 = 1행"에서 "문단 1개 = 1행"으로 변경
--
-- 배경 (실제 보장 API 응답에서 확인)
--   SNTC_LINE_NO 2 : GIVE_BNFI_NM='■ 사망보험금', 문구='보험기간 중 사망하였을 때'
--   SNTC_LINE_NO 3 : (빈 줄)
--   SNTC_LINE_NO 4~6 : GIVE_BNFI_NM=''(비어 있음), 문구='* 보험료 납입기간 중 … 납입을 면제합니다.'
--   → 납입면제 문구는 별도 급부명 없이 사망보험금 아래 각주(*)로 붙어 있다.
--   → 급부명 기준으로 이어붙이면 납입면제 조건이 사망보험금 조건에 섞인다.
--
-- 변경
--   * 빈 줄로 구분된 문단을 1행으로 저장
--   * source_line_no : 문단이 시작하는 원본 SNTC_LINE_NO (자연키, upsert 기준)
--   * is_note        : '*'로 시작하는 부가조건 문단 여부 (예: 납입면제)
--
-- V1은 이미 적용되었으므로 수정하지 않고 새 버전으로 변경한다.
-- 이 시점에 coverage는 비어 있다(적재 전). 데이터가 있으면 NOT NULL 단계에서 실패한다.
-- =====================================================================

ALTER TABLE coverage DROP CONSTRAINT uq_coverage;

ALTER TABLE coverage ADD COLUMN source_line_no SMALLINT;
ALTER TABLE coverage ADD COLUMN is_note BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE coverage ALTER COLUMN source_line_no SET NOT NULL;

ALTER TABLE coverage ADD CONSTRAINT uq_coverage_paragraph UNIQUE (version_id, source_line_no);
ALTER TABLE coverage ADD CONSTRAINT ck_coverage_line_no CHECK (source_line_no > 0);

COMMENT ON COLUMN coverage.benefit_name   IS '소속 급부명(■ 기호 제거). 각주 문단은 바로 위 급부명을 따른다';
COMMENT ON COLUMN coverage.source_line_no IS '문단 시작 SNTC_LINE_NO (원본 추적용 자연키)';
COMMENT ON COLUMN coverage.is_note        IS '* 로 시작하는 부가조건 문단 (예: 납입면제)';
