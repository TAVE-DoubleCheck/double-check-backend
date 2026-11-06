# 미니 보험 코어

## 1. 로컬 DB

```bash
cp .env.example .env          # DATA_GO_KR_SERVICE_KEY에 Decoding 키 입력
docker compose up -d          # PostgreSQL 기동 → Flyway가 마이그레이션 적용 후 종료
docker compose logs flyway    # "Successfully applied N migrations" 확인
```

접속: `localhost:5432` / DB `insurance` / 계정 `insurance` / 비밀번호 `insurance` (기본값)
초기화(데이터 전부 삭제): `docker compose down -v && docker compose up -d`

## 2. 상품 데이터 적재 (F1)

필요: JDK 17 이상, Docker Desktop 실행 중

```bash
./gradlew bootRun                              # product가 비어 있으면 적재 후 종료
./gradlew bootRun --args='--ingest.force=true' # 강제 재적재 (행 수는 그대로여야 함)
```

앱이 기동하면 Flyway가 미적용 마이그레이션(V3)을 먼저 적용하고 적재를 시작한다.
마지막에 `[적재 리포트]`가 출력되고, 같은 내용이 `ingest_log.message`에 남는다.

```
API 원문 ─▶ raw_api_response (호출마다 저장)
        └▶ 파싱 ─▶ 전처리 ─▶ 매핑·검증(IngestPlanner) ─▶ 한 트랜잭션 upsert(IngestWriter)
                                       └ 실패 시 DB 변경 없음, ingest_log = FAILED
```

## 3. 테스트

```bash
./gradlew test
```

| 테스트 | 내용 |
|---|---|
| `NormalizerTest` | 금액·기간·날짜 변환, 관찰되지 않은 형식 거부 |
| `ApiResponseParserTest` | 실제 응답 3종, item 1개/0개, XML·오류 코드 |
| `CoverageMergerTest` | 납입면제 각주가 사망보험금 조건과 분리되는지 (실제 응답) |
| `IngestPlannerTest` | 매핑·제외·중복·누락·버전 검증, 165,350원 |
| `IngestServiceIntegrationTest` | Testcontainers PostgreSQL에서 전체 적재 2회 → 행 수 동일, 실패 시 롤백 + 원문 보관 |

## 4. 마이그레이션

| 파일 | 내용 |
|---|---|
| `V1__product_schema.sql` | product, product_version, premium_rate, coverage, ingest_log, raw_api_response |
| `V2__system_clock.sql` | system_clock (1행, 앞으로만 이동) |
| `V3__coverage_paragraph.sql` | coverage를 문단 단위로 변경 (source_line_no, is_note) |

**이미 적용된 V 파일은 수정하지 않는다.** 바꾸려면 새 V 파일을 추가한다 (체크섬 불일치로 기동 실패).

## 5. 실제 응답에서 확인된 사실 (2026-10-27)

| 사실 | 반영 |
|---|---|
| 약관코드 = "'P' + 상품코드 앞 6자리" 규칙이 **틀림** (4008300 → 실제 `P400083`, 규칙상 `P400830`) | 약관 URL의 `p` 값을 그대로 사용. 규칙 검증 제거 |
| 보장 API의 납입면제 문구는 급부명 없이 사망보험금 아래 `*` 각주로 옴 | 문단 단위 저장, `is_note` 표시 (V3) |
| 보장 API는 페이지가 나뉨, `totalCount`가 페이지 크기와 같게 옴 | 덜 찬 페이지가 나올 때까지 반복 |
| 보장 API에도 적용시작일이 있음 | 보장 문단은 적용시작일로 버전에 정확히 연결 |
| 보험료 API에 `CTRT_DVSN_NM`(주계약 등) 필드 | 주계약만 적재 |
| 0세 가입 상품 존재 (`(무)안전벨트`) | 나이 범위 0~80 호출, 0건 나이는 리포트 |

## 6. 가정과 확인 필요

| 항목 | 현재 처리 |
|---|---|
| **가정:** 보험료 API에 적용기간이 없음 | 적재 시점(system_clock) 기준 유효 버전의 요율로 저장 |
| 확인 완료: 상세기능 경로·파라미터 | 약관 `/InsuranceTerms?GDS_NM=`, 보장 `/InsuranceGuranteeContent?INSU_NM=&numOfRows=&pageNo=` (철자 원본 그대로), 보험료 `/InsuranceFee`. 형식 파라미터 없이 JSON |
| 확인 필요: 보험기간 60·70의 의미 | `insure_term_code`에 원본 그대로 저장 |
| 확인 필요: 비종신 4종의 보장 API 정식 상품명 | 리포트의 "매핑 없는 보장 상품명"을 보고 `coverage-name-map`에 추가 |
| 확인 필요: 약관 URL의 `d` 파라미터 | 조회일로 보임. 원본 그대로 저장 |

설정에서 API 파라미터 이름은 `"[GDS_NM]"`처럼 대괄호로 감싼다. 대괄호가 없으면 Spring이 `_`를 지운다.
