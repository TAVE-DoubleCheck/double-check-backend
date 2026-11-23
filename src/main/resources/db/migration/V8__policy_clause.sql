-- =====================================================================
-- V8: 약관 조항 (시뮬레이터 결과 문장의 근거)
--   데이터는 seed/reference_seed.sql
-- =====================================================================
-- 약관 조항 테이블: 시뮬레이터 결과 문장의 근거 (설명 기능 ① 규칙 기반 설명)
-- 배경: 엔진 결과(예: 실효)마다 근거 조항이 결정적으로 정해지므로 검색(RAG) 없이 clause_key로 조회한다.
--       2단계에서 RAG를 붙이면 이 테이블을 검색 대상으로 그대로 재사용한다.
-- 원문 출처: 무배당 우체국하나로OK보험 2609 약관 (P400083~P400090 공통, 공시실 상품목록 '약관보기')
--          original_text는 PDF 텍스트를 그대로 잘라 넣음 (줄바꿈·쪽번호만 제거)
-- plain_summary는 팀이 쓴 쉬운 요약. 화면에는 summary를 보여주고 '원문 보기'로 original_text 노출

CREATE TABLE policy_clause (
    clause_key     VARCHAR(30)  PRIMARY KEY,          -- 예: ART28_1 (제28조 제1항)
    doc_code       VARCHAR(60)  NOT NULL,             -- 약관 문서 식별자
    article_no     INT,                               -- 조 (별표는 NULL)
    paragraph_no   INT,                               -- 항 (조 전체·별표는 NULL)
    title          VARCHAR(100) NOT NULL,
    original_text  TEXT         NOT NULL,
    plain_summary  TEXT         NOT NULL,
    source_page    INT          NOT NULL,             -- 약관 PDF 쪽
    event_code     VARCHAR(40)  NOT NULL UNIQUE,      -- 이 조항을 근거로 쓰는 엔진 결과 코드
    CONSTRAINT ck_policy_clause_article CHECK (article_no IS NULL OR article_no > 0),
    CONSTRAINT ck_policy_clause_para CHECK (paragraph_no IS NULL OR paragraph_no > 0)
);

COMMENT ON COLUMN policy_clause.event_code IS '엔진 결과 → 근거 조항 1:1 매핑. 코드 이름은 팀 합의 전 제안';
