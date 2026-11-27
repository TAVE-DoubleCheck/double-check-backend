# 보험금 청구 접수 (BE C)

소유: BE C (청구·지급). 코드는 `kr.tave.minicore.claim` 패키지.

## API

| 메서드 | 주소 | 누가 | 내용 |
| --- | --- | --- | --- |
| POST | `/api/claims` | 고객 | 장해 청구 접수 (내 계약만) |
| POST | `/api/admin/claims` | 직원 | 사망 청구 대리 접수 |

### 장해 청구 접수

```json
POST /api/claims
{ "contractId": 12, "type": "DISABILITY", "accidentDate": "2026-05-20", "accidentDescription": "작업 중 손목 절단" }
```

### 사망 청구 대리 접수

```json
POST /api/admin/claims
{ "contractId": 12, "deathDate": "2026-05-20", "claimantName": "이수익", "claimantRelation": "SPOUSE" }
```

- `claimantRelation`: 계약 수익자 관계와 같은 코드 (`SPOUSE` `CHILD` `PARENT` `SIBLING`)
- 수익자 일치(점검 5)는 접수 때 막지 않고 심사 때 확인

### 응답 (201)

```json
{
  "claimId": 31, "contractId": 12, "type": "DISABILITY", "status": "RECEIVED",
  "accidentDate": "2026-05-20", "claimantName": "김고객", "claimantRelation": "SELF",
  "intakeChannel": "CUSTOMER", "receivedOn": "2026-07-01"
}
```

## 접수 단계 자동 점검

| # | check | 내용 | 대상 | 실패 시 거절 사유 |
| --- | --- | --- | --- | --- |
| 1 | `COVERED_ON_ACCIDENT_DATE` | 사고일 당시 계약이 보장 중 (ACTIVE·PAID_UP·PREMIUM_WAIVED) | 사망·장해 | `NOT_COVERED` |
| 2 | `AFTER_CONTRACT_START` | 계약일(첫 보험료 낸 날) 이후 사고 | 사망·장해 | `BEFORE_CONTRACT` |
| 3 | `WITHIN_PAY_TERM` | 계약일 + 납입년수 이전 사고 | 장해 | `AFTER_PAY_TERM` |
| 6 | `NOT_DUPLICATE` | 거절 안 된 같은 종류 청구 없음 | 사망·장해 | `DUPLICATE` |

- 점검 4(장해지급률 50%)·5(수익자 일치)는 심사 단계
- 사고일 당시 상태는 `ContractStatusService.statusOn(contractId, accidentDate)` (BE B)
- 실패 항목을 전부 모아 한 번에 응답

```json
409
{
  "code": "CLAIM_NOT_ACCEPTABLE",
  "message": "사고일에 보장 중인 계약이 아닙니다",
  "details": { "failedChecks": ["COVERED_ON_ACCIDENT_DATE"] }
}
```

### 예시 (약관 제28조①)

| 사고일 | 사고일 상태 | 결과 |
| --- | --- | --- |
| 5/20 | ACTIVE | 접수 |
| 6/1 (실효일) | LAPSED | 409 `COVERED_ON_ACCIDENT_DATE` |
| 6/2 | LAPSED | 409 `COVERED_ON_ACCIDENT_DATE` |

계약: 3/15 성립 → 3/15 납입기일 미납 → 5/31 유예 종료 → 6/1 실효

## 판정 규칙 재사용 (시뮬레이터)

- 판정은 `ClaimEligibility.checkIntake(IntakeFacts)`: DB·스프링 없는 순수 함수
- 실제 접수는 DB에서 사실을 모아 넣고, 시뮬레이터는 예상 사실을 넣어 같은 함수 호출
- 같은 규칙이라 "예상 결과 = 실제 처리 결과"를 테스트로 대조 가능

```java
List<ClaimCheck> failed = ClaimEligibility.checkIntake(new IntakeFacts(
        ClaimType.DISABILITY, accidentDate, statusOnAccidentDate, contractDate, payTermYears, false));
boolean covered = failed.isEmpty();
```

## 중복 접수 방어

| 장치 | 막는 것 |
| --- | --- |
| 점검 6 (조회) | 일반적인 중복 접수 → 409 |
| DB 부분 UNIQUE `uq_claim_active (contract_id, type) WHERE status <> 'REJECTED'` | 두 요청이 점검 6을 동시에 통과한 경우 → DB 오류를 409 `NOT_DUPLICATE`로 변환 |

- 거절된 청구는 자리에서 빠지므로 재청구 가능

## 오류

| code | HTTP | 상황 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | 필수값 누락, 고객 주소에 `DEATH`, 사고일이 업무 날짜 이후 |
| `CONTRACT_NOT_FOUND` | 404 | 없는 계약, 고객이 남의 계약 |
| `FORBIDDEN` | 403 | 고객이 직원 주소 호출 |
| `CLAIM_NOT_ACCEPTABLE` | 409 | 접수 점검 실패 (`details.failedChecks`) |

## 기록

- `claim` 1행: `status = RECEIVED`, `received_on = 업무 날짜`, `created_by = 로그인 계정`
- `claim_history` 1행: `NULL → RECEIVED`, `actor_id = 로그인 계정`
