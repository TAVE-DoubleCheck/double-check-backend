# 미니 보험 코어 — 상품 데이터 영역 (F1)

공공데이터포털 우체국보험 API 3종(약관 / 보장내용 / 1회 보험료계산)을 PostgreSQL에 적재하고,
적재된 데이터를 읽는 상품 조회·보험료 계산 API를 제공한다.
서비스 코드는 공공 API를 직접 호출하지 않고 이 DB만 조회한다.

## 1. 사전 준비

- **JDK 17 이상** (`java -version`)
- **Docker Desktop** 실행 중일 것 (DB 기동과 `./gradlew test` 모두 필요)

## 2. 처음 클론했을 때

```bash
cp .env.example .env          # 직접 적재할 때만 DATA_GO_KR_SERVICE_KEY(Decoding 키) 입력
docker compose up -d          # PostgreSQL 기동 → Flyway 컨테이너가 마이그레이션 적용 후 종료(정상)
docker compose logs flyway    # "Successfully applied ..." 확인
```

접속: `localhost:5432` / DB `insurance` / 계정 `insurance` / 비밀번호 `insurance` (기본값)

## 3. 데이터 넣기

### A. seed 복원 (기본 — API 키 불필요, CI도 이 방식)

```bash
docker compose exec -T postgres psql -U insurance -d insurance -v ON_ERROR_STOP=1 -f - < seed/product_seed.sql
docker compose exec -T postgres psql -U insurance -d insurance -v ON_ERROR_STOP=1 -f - < seed/reference_seed.sql  # product_seed 다음에 (product FK)
```

- 빈 테이블에만 복원할 것 (중복 복원 시 PK 충돌로 멈추는 것이 정상)
- 완전 초기화 후 재시도: `docker compose down -v && docker compose up -d` (**로컬 데이터 전부 삭제** 주의)
- 복원 결과: product 8 / product_version 8 / premium_rate 5,352 / coverage 20
- reference_seed 복원 결과: disclosed_rate 90 / loan_rate_spread 1 / surrender_example_set 4 / surrender_example 36 / policy_clause 16

### B. 직접 적재 (선택 — API 키 필요, 일일 호출 한도 주의)

```bash
./gradlew bootRun                              # product가 비어 있으면 적재 후 종료
./gradlew bootRun --args='--ingest.force=true' # 강제 재적재 (행 수는 그대로여야 함 = 멱등)
```

마지막에 `[적재 리포트]`가 출력되고 같은 내용이 `ingest_log.message`에 남는다.
1회 적재 호출 수: 약관 1 + 보험료 142(나이 0~70 × 성별 2) + 보장 1 ≈ **144회**.

```
API 원문 ─▶ raw_api_response (호출마다 저장)
        └▶ 파싱 ─▶ 전처리 ─▶ 매핑·검증(IngestPlanner) ─▶ 한 트랜잭션 upsert(IngestWriter)
                                       └ 실패 시 DB 변경 없음, ingest_log = FAILED
```

## 4. 테스트

```bash
./gradlew test    # 단위 36 + 통합 22 = 58개 (Docker Desktop 필수)
```

| 테스트 | 내용 |
|---|---|
| `NormalizerTest` | 금액·기간·날짜 변환, 관찰되지 않은 형식 거부 |
| `ApiResponseParserTest` | 실제 응답 3종, item 1개/0개, XML·오류 코드 |
| `CoverageMergerTest` | 납입면제 각주가 사망보험금 조건과 분리되는지 (실제 응답) |
| `IngestPlannerTest` | 매핑·제외·중복·누락·버전 검증, 165,350원 |
| `InsuranceAgeTest` | 보험나이 (약관 제23조② 예시·경계값·윤년) |
| `PremiumCalculatorTest` | 월 보험료 정수 계산, 단위 배수 검증 |
| `IngestServiceIntegrationTest` | Testcontainers PostgreSQL에서 전체 적재 2회 → 행 수 동일, 실패 시 롤백 + 원문 보관 |
| `SanityCheckIntegrationTest` | seed(product + reference) 복원 후 `checks/` 검증 5종이 위반 0행인지 |
| `ProductApiIntegrationTest` | seed 위에서 API 3개: 목록·상세·quote 정상/멱등/버전 고정 + 실패 응답 코드 |
| `JwtProviderTest` | 토큰 발급·검증, 만료·다른 키·내용 변조 거부, 짧은 서명 키 기동 실패 |
| `AuthApiIntegrationTest` | 회원가입(BCrypt 저장·중복 409·형식 400) → 로그인 → 내 정보, 401·403, 직원 계정 자동 생성, 상품 조회 공개 |

### 맥(Apple Silicon)에서 통합 테스트가 Docker를 못 찾을 때

- 증상: `ContainerFetchException` ← `Could not find a valid Docker environment`
- 원인: **Docker Engine 29부터 구형 Docker API(v1.32) 호출을 400으로 거부**한다.
  Spring Boot 3.5.6이 관리하는 Testcontainers 1.21.3이 v1.32로 호출해서 실패한다.
- 해결: `build.gradle`의 `ext['testcontainers.version'] = '1.21.4'` (이미 적용됨).
  그래도 실패하면 ① Docker Desktop이 실행 중인지, ② `docker version`의 Server 응답이 나오는지 확인.

## 5. 상식 검증 (checks/)

적재된 요율이 보험 원리에 맞는지 확인하는 SQL. 전처리 실수·컬럼 밀림을 잡는 장치다.
SELECT만 사용하므로 읽기 전용 계정으로 그대로 실행 가능. **결과 0행 = 통과**
(위반이 있으면 위반 행을, 비교 쌍이 아예 없으면 `NO_PAIRS` 행을 반환한다).

```bash
docker compose exec -T postgres psql -U insurance -d insurance -f - < checks/01_refund50_lt_standard.sql
```

| 파일 | 검증 | 결과 |
|---|---|---|
| `00_product_class.sql` | 형/종 분류 규칙 정의 (상품명 파싱 — 01~03의 CTE와 동일해야 함) | — |
| `01_refund50_lt_standard.sql` | 해약환급금50%형 < 표준형 | 쌍 2,676 / 위반 0 |
| `02_simplified_gt_general.sql` | 2종(간편가입) > 1종(일반가입) | 쌍 2,460 / 위반 0 |
| `03_longer_pay_term_cheaper.sql` | 납입기간 길수록 월 보험료 감소 | 쌍 9,516 / 위반 0 |
| `04_surrender_half_rule.sql` | 해약환급금 예시표가 약관 50% 규칙을 지키는지 (납입 중 50%형 = 표준형의 50%, 10원 이하 차이 허용 / 납입 완료 후 동일) | 쌍 18 / 위반 0 |
| `05_example_premium_matches_rate.sql` | 상품요약서 예시 보험료 = API로 적재한 요율표 보험료 (공시 문서와 API 데이터 일치) | 상품 4 / 위반 0 |

결과 기준: 01~03은 2026-11-06 적재본(premium_rate 5,352행), 04~05는 2026-11-23 reference_seed

위반이 나오면 데이터를 고치지 말고 그대로 보고할 것 (실제 상품 특성일 수 있음 → 약관 확인 대상).

## 6. 상품 API (F1 조회 — 비로그인 공개)

앱 기동: `./gradlew bootRun` (DB 필요). Swagger UI: **http://localhost:8080/swagger-ui/index.html**

| Method | Endpoint | 내용 |
|---|---|---|
| GET | `/api/products` | 오늘(업무 날짜) 유효 버전이 있는 상품 목록. `subscribable`로 1단계 가입 가능 구분 |
| GET | `/api/products/{code}` | 상세: 버전, 기준단위금액, 가입금액 범위, 성별 가입나이 범위, 납입 플랜(`payTermPlans`), 보장 문단 |
| GET | `/api/products/{code}/quote` | 보험료 계산. 파라미터: `birthDate`(YYYY-MM-DD), `sex`(M\|W), `insureTermCode`, `amount`(원), 그리고 **`payTermYears` 또는 `payTermPlan` 중 정확히 하나** (`FIXED_YEARS_<년수>` \| `TO_AGE_<나이>`, 예: `TO_AGE_80`) |

### 납입 플랜 (payTermPlans)

요율표의 납입기간 DISTINCT(종신 기준 56개)를 그대로 주는 대신, 요율표에서 유도한 플랜으로 묶어 준다:
`FIXED_YEARS`(10/15/20/30년납) + `TO_AGE`(toAge 80). 각 플랜에 성별별 가입 가능 보험나이 범위 포함.

- **"80세납"은 원본 표기가 아니다.** 원본 `PYMT_TRM_S10`은 `"N년"`(과 타 상품군의 `"전기납"`)뿐이고,
  one-off 납입년수들이 전부 **N = 80 − 가입나이** 관계를 만족해 `TO_AGE(80)`으로 유도한 것이다
  (유도 로직·안전장치: `PayTermPlanAssembler`. 만기 나이가 하나로 수렴하지 않으면 묶지 않음).
- quote에서 `payTermPlan=TO_AGE_80`을 보내면 서버가 `80 − 보험나이`로 납입년수를 변환한다.
  보험나이 80세 이상이면 요율 조회 전에 `RATE_NOT_FOUND`.

- 업무 날짜는 `system_clock.business_date` 기준 (`ClockService`). `LocalDate.now()`를 쓰지 않는다.
- 가입 처리(BE B)는 같은 `QuoteService`를 호출해 `versionId`·`monthlyPremium`·`insuranceAge`를
  계약에 그대로 고정한다 → **`docs/quote-contract.md`** 참고.

### 오류 응답

형식: `{ "code": "RATE_NOT_FOUND", "message": "..." }`

| HTTP | code | 언제 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 로그인이 필요한 주소에 토큰 없음·만료·위조 |
| 401 | `INVALID_CREDENTIALS` | 로그인 시 이메일 또는 비밀번호가 틀림 (둘을 구분하지 않음) |
| 403 | `FORBIDDEN` | 고객이 직원 전용 주소(`/api/admin/**`) 호출 |
| 409 | `EMAIL_ALREADY_EXISTS` | 이미 가입된 이메일 (대소문자 무시) |
| 400 | `VALIDATION_ERROR` | 필수값 누락, 날짜·형식 오류 |
| 400 | `AMOUNT_NOT_UNIT_MULTIPLE` | 가입금액이 기준단위금액(1,000만 원) 배수가 아님 |
| 400 | `AMOUNT_OUT_OF_RANGE` | 가입금액이 설정 범위(1,000만~1억) 밖 |
| 400 | `BIRTH_DATE_IN_FUTURE` | 생년월일이 계약일 이후 |
| 404 | `PRODUCT_NOT_FOUND` | 상품코드 없음 |
| 409 | `NO_EFFECTIVE_VERSION` | 오늘 유효한 상품 버전 없음 |
| 409 | `PRODUCT_NOT_SUBSCRIBABLE` | 1단계 가입 불가 상품 (비종신 4종) |
| 409 | `RATE_NOT_FOUND` | 요율표에 조합 없음 (메시지에 나이 범위 힌트 포함) |

### 단순화 (코드 주석과 동일 — 해소되면 양쪽 모두 갱신)

| 항목 | 1단계 처리 | 상태 |
|---|---|---|
| 고액계약 할인 | 미반영, 정비례 (`PremiumCalculator` 한 곳) | 기준·비율은 사업방법서 영역. **확인 필요** |
| 납입주기 | 월납 가정 | 요율 API에 납입주기 필드 없음. **확인 필요** |
| 가입금액 단위 | 1,000만 원 배수만 허용 | 원 단위 반올림 규정이 약관에 없어 입력에서 차단 (우리 결정) |
| 가입금액 범위 | 1,000만~1억 (`quote.amount-*-units` 설정) | 사업방법서 미확인. **확인 필요** |
| 가입 가능 상품 | 종신 4종 (`product.subscribable-codes` 설정) | 비종신은 플러스보험기간 산출방법서 없이 구현 불가 (약관 제20조④, 안건 2) |
| 가입나이 범위 | 요율표 존재 여부로 판단 | 약관에 최저·최고 나이 없음 |
| 보험나이 | 약관 제23조② 산식 | 확정. 안건 5에서 추인 예정 |
| ClockService | 임시 JDBC 구현 (`clock/`) | BE D가 확정하면 교체 |

## 로그인 API

JWT 방식. 로그인 응답의 `accessToken`을 요청마다 `Authorization: Bearer {accessToken}` 헤더로 보낸다.
Swagger에서는 오른쪽 위 **Authorize** 버튼에 토큰을 붙여 넣는다. 자세한 규칙·사용법은 **`docs/auth.md`**.

| Method | Endpoint | 로그인 | 내용 |
|---|---|---|---|
| POST | `/api/auth/signup` | 불필요 | 고객 회원가입 (email, password, name, birthDate, sex) → 201 |
| POST | `/api/auth/login` | 불필요 | 로그인 → `accessToken`, `expiresIn`(초), `user` |
| POST | `/api/auth/logout` | 필요 | 204. 서버 저장 정보가 없어 프론트가 토큰을 지우면 끝 |
| GET | `/api/auth/me` | 필요 | 내 정보 |

- 공개 주소: 회원가입·로그인, `GET /api/products/**`, Swagger, `/actuator/health`. 직원 전용: `/api/admin/**`. 그 외는 로그인 필요
- 직원 계정은 앱 기동 시 자동 생성 (기본 `admin@doublecheck.local` / `admin1234!`, `.env`의 `ADMIN_EMAIL`·`ADMIN_PASSWORD`로 변경)

## 시스템 날짜 API

`GET /api/clock`은 `ClockService`를 통해 DB의 업무 날짜를 `{ "businessDate": "YYYY-MM-DD" }`로 반환한다.
로그인한 고객과 직원 모두 사용할 수 있으며, 미인증 요청은 401이다.
현재 브랜치에는 JWT 인증 모듈이 없어 인증 모듈 연동 전에는 실제 요청이 401을 반환한다.
응답 형식과 인증 연동 방법은 [시스템 날짜 API](docs/clock-api.md)를 참고한다.

## 하루 업무 처리

`DailyWorkService.advanceTo(targetDate)`는 날짜를 하루씩 이동하면서 청구서 발행 → 자동이체 →
미납·실효 → 만기 → 장부 대사를 순서대로 호출하는 기본 구조이다.
날짜 변경과 하루 업무를 함께 커밋하며 실패한 날짜는 롤백한다. 동시 날짜 이동은 DB 행 잠금으로 조정한다.
실제 단계 구현은 별도 작업이며, 미구현 단계가 있으면 날짜 이동을 거부한다.
아직 외부 날짜 변경 API는 제공하지 않는다. 연결 방법과 트랜잭션 규칙은
[하루 업무 처리](docs/daily-work.md)를 참고한다.

## 루트 경로

로컬 또는 배포 서버의 루트 주소(`/`)에 접속하면 HTTP 302로 `/swagger-ui/index.html`로 이동한다.
리다이렉트는 `GET /`에만 적용하며 상품 API 및 `/actuator/health` 경로는 변경하지 않는다.
Render Health Check Path는 루트가 아닌 `/actuator/health`로 유지한다.
추후 인증 모듈 도입 시 `/`, `/swagger-ui/**`, `/v3/api-docs/**`의 공개 접근을 허용해야 한다.

## 서버·DB 상태 확인

`GET /actuator/health`는 로그인 없이 서버와 DB 연결 상태를 확인한다.
정상이면 HTTP 200과 `{"status":"UP"}`, DB 연결 실패이면 HTTP 503과 `{"status":"DOWN"}`을 반환한다.
DB 주소나 오류 상세는 공개하지 않는다. Render의 Health Check Path에 `/actuator/health`를 지정한다.
설정과 검증 방법은 [헬스 체크 API](docs/health-api.md)를 참고한다.

## ledger

V9는 보험료 입금·보험금 출금 ledger와 DB 보호 규칙을 추가한다.
원본 완료 상태·계약·금액·거래 날짜 일치, 중복 기록 금지, 수정·삭제 금지를 DB에서 검사한다.
`LedgerService`는 청구서·지급건 ID로 원본을 조회해 기록하며, 중복 요청에는 기존 기록을 반환한다.
호출자의 트랜잭션이 필수이며, 원본의 완료 처리와 ledger 기록을 함께 커밋·롤백한다.
별도 HTTP API는 없다. 테스트용 TRUNCATE는 허용한다. 실행 계정 권한 및 서비스 연동 기준은
[ledger 스키마](docs/ledger-schema.md)를 참고한다.

## 7. 마이그레이션

| 파일 | 내용 |
|---|---|
| `V1__product_schema.sql` | product, product_version, premium_rate, coverage, ingest_log, raw_api_response |
| `V2__system_clock.sql` | system_clock (1행, 앞으로만 이동) |
| `V3__coverage_paragraph.sql` | coverage를 문단 단위로 변경 (source_line_no, is_note) |
| `V9__ledger_schema.sql` | ledger 및 원본 일치·중복·수정·삭제 방지 규칙 |

**이미 적용된 V 파일은 수정하지 않는다.** 바꾸려면 새 V 파일을 추가한다 (체크섬 불일치로 기동 실패).

## 8. 확정된 도메인 사실

| 사실 | 근거 / 반영 |
|---|---|
| 가입나이: 1종 15~70세, 2종 **남 20~66세 / 여 20~70세** (성별로 상한이 다름) | 0~80세 전체 호출 원문 + 요율표 성별 집계. 0~14세 응답 0건, 71~80세엔 다른 상품군(4102901 등)만 응답 |
| `insure_term_code`: `99`=종신, 그 외 = **만기까지 남은 연수** (가입나이 + 코드 = 90 또는 100, 전 행 성립) | 5,352행 전수 검증. "60/70" 고정이 아니라 나이마다 달라짐 |
| 납입기간은 5개 플랜: 10/15/20/30년납 + 80세납. 단 **"80세납"은 원본 표기가 아니라 "N년 = 80 − 가입나이" 관계로 유도한 라벨** (원본 값은 "N년"뿐, 우리 8종에 전기납 없음) | 원본 5,352행 = 적재 5,352행 1:1 확인(중복 키 0). 납입 종료 나이 ≤ 80이 전 상품 성립 |
| 2종 남성 55~66세는 80세납(TO_AGE_80) 불가 — 플랜별 범위도 성별로 다름 | 요율표 집계 (2종 M의 TO_AGE_80 범위 20~54세, W는 20~70세) |
| `전기납`은 하나로OK보험 8종엔 없음 (`is_full_term_pay` 전부 false) | 다른 상품군(4102901)에만 존재, 범위 제외됨 |
| coverage 20문단 = 종신 4종×2 + 비종신 4종×3 | 비종신에만 `플러스사망보험금` 문단 추가 |
| 보험료 상식 관계(50%<표준, 2종>1종, 납입기간↑→월보험료↓) 위반 0건 | `checks/` 참조 |
| 약관코드 = "'P'+상품코드 앞 6자리" 규칙 **틀림** (4008300 → 실제 `P400083`) | 약관 URL의 `p` 값을 그대로 사용 |
| 보장 API 납입면제 문구는 급부명 없이 `*` 각주로 옴 | 문단 단위 저장, `is_note` 표시 (V3) |
| 보장 API는 페이지 분할, `totalCount`가 페이지 크기와 같게 옴 | 덜 찬 페이지가 나올 때까지 반복 |
| 보험료 API `CTRT_DVSN_NM`(주계약 등) | 주계약만 적재 |

## 9. 확인 필요 (팀 논의 / 약관 원문 대상)

| 항목 | 현재 처리 |
|---|---|
| `system_clock` 초기값이 UTC 기준 날짜로 들어감 (KST 새벽에 마이그레이션하면 전날짜) | 현상 보고만. 업무 날짜 기준(KST 여부)은 팀 확정 필요 |
| **플러스보험기간 / 플러스사망보험금**의 의미 | API 문구는 "플러스보험기간 중 사망하였을 때"뿐 → 약관 원문 확인 필요 |
| **가정:** 보험료 API에 적용기간이 없음 | 적재 시점(system_clock) 기준 유효 버전의 요율로 저장 |
| 약관 URL의 `d` 파라미터 | 조회일로 보임. 원본 그대로 저장 |

설정에서 API 파라미터 이름은 `"[GDS_NM]"`처럼 대괄호로 감싼다. 대괄호가 없으면 Spring이 `_`를 지운다.
