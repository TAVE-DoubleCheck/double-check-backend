# 보험금 청구 심사·승인·거절 (BE C)

소유: BE C. 코드는 `kr.tave.minicore.claim` (`ClaimReviewService`, `AdminClaimController`). 접수는 `docs/claim-intake.md`.

## 흐름

```
RECEIVED ─심사 시작→ REVIEWING ─승인(점검 6가지 재확인)→ APPROVED ─(사망) 지급 실행→ PAID   (지급 이슈)
                         │                              └(장해) 즉시 → CLOSED + 계약 PREMIUM_WAIVED
                         ├─ (장해) 지급률 판정 기록 추가 (여러 번 가능, 최신 값 사용)
                         └─ 거절(사유 필수)→ REJECTED  (같은 종류 재청구 가능)
```

## API (직원 전용, `/api/admin/**`)

| 메서드 | 주소 | 내용 |
| --- | --- | --- |
| POST | `/api/admin/claims/{id}/review` | 심사 시작 RECEIVED → REVIEWING |
| POST | `/api/admin/claims/{id}/assessments` | 장해지급률 판정 기록 추가 (장해·REVIEWING만) |
| POST | `/api/admin/claims/{id}/approve` | 승인 (본문 없음) |
| POST | `/api/admin/claims/{id}/reject` | 거절 |

### 판정 기록

```json
POST /api/admin/claims/31/assessments
{ "disabilityRate": 60, "basis": "별표3 8.팔 1) 한 팔의 손목 이상을 잃었을 때 (60)", "reason": "장해진단서 확인" }
```

- 0~100. 고치지 않고 새 기록 추가 → 가장 최근 기록이 현재 값 (`claim_assessment` 추가만)
- 청구 상태는 바뀌지 않음

### 승인 응답

```json
{ "claimId": 40, "type": "DEATH", "status": "APPROVED", "decidedOn": "2026-07-01", "payoutId": 5, "payoutAmount": 10000000 }
{ "claimId": 31, "type": "DISABILITY", "status": "CLOSED", "decidedOn": "2026-07-01", "contractStatus": "PREMIUM_WAIVED" }
```

### 거절

```json
POST /api/admin/claims/31/reject
{ "reason": "DISABILITY_RATE_UNDER_50", "memo": "척추 같은 부위 → 높은 것 40%" }
```

사유: `NOT_COVERED` `BEFORE_CONTRACT` `AFTER_PAY_TERM` `DISABILITY_RATE_UNDER_50` `BENEFICIARY_MISMATCH` `DUPLICATE` `EXEMPTION_ARTICLE_5`

## 승인 점검 (`ClaimEligibility.checkApproval`)

접수 점검을 승인 시점 값으로 **다시** 하고 4·5를 더한다. 접수 뒤 판정·다른 청구 상태가 바뀔 수 있어서.

| # | check | 대상 | 실패 시 거절 사유 |
| --- | --- | --- | --- |
| 1 | `COVERED_ON_ACCIDENT_DATE` | 사망·장해 | `NOT_COVERED` |
| 2 | `AFTER_CONTRACT_START` | 사망·장해 | `BEFORE_CONTRACT` |
| 3 | `WITHIN_PAY_TERM` | 장해 | `AFTER_PAY_TERM` |
| 4 | `DISABILITY_RATE_50` | 장해: 최신 판정 ≥ 50 (없으면 실패) | `DISABILITY_RATE_UNDER_50` |
| 5 | `BENEFICIARY_MATCH` | 사망: 청구인 이름·관계 = 계약 수익자 | `BENEFICIARY_MISMATCH` |
| 6 | `NOT_DUPLICATE` | 사망·장해 (이 청구 제외) | `DUPLICATE` |

```json
409
{
  "code": "CLAIM_CHECK_FAILED",
  "message": "자동 점검 실패: 장해지급률이 50% 미만입니다",
  "details": { "failedChecks": ["DISABILITY_RATE_50"], "suggestedRejectReasons": ["DISABILITY_RATE_UNDER_50"] }
}
```

- 실패하면 아무것도 바뀌지 않음. 화면은 `suggestedRejectReasons`로 거절 화면을 바로 채울 수 있음

## 승인 결과 (한 트랜잭션)

| 종류 | 청구 | 그 밖 |
| --- | --- | --- |
| 사망 | APPROVED, `decided_on` = 업무 날짜 | `payout` 1건 PENDING, 금액 = `contract.insured_amount` |
| 장해 | APPROVED → CLOSED (이력 2줄) | `ContractStatusService.waivePremium(contractId, decided_on, …)` → 계약 PREMIUM_WAIVED |

- 장해 승인은 돈이 나가지 않음. `decided_on` 이후 청구서부터 납입면제 (회의 B-5)
- 계약이 ACTIVE가 아니면(예: 사고 후 실효) `waivePremium`이 409 `INVALID_CONTRACT_STATUS` → 승인 전체 취소

## 동시 처리

| 상황 | 결과 |
| --- | --- |
| 직원 두 명이 같은 청구를 동시에 승인·거절 | 1명만 성공. 늦은 쪽 409 `CONCURRENT_UPDATE`(@Version) 또는 `INVALID_CLAIM_STATUS` |
| 사망 동시 승인 | 지급건 1개 |

- 승인 시 **청구 변경을 먼저 flush**한 뒤 지급건 생성. 순서가 반대면 늦은 쪽이 `uq_payout_claim` 위반(500)으로 실패함 (flush를 빼고 테스트하면 재현됨)

## 오류

| code | HTTP | 상황 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | 지급률 범위 밖, 거절 사유 누락·없는 값 |
| `FORBIDDEN` | 403 | 고객이 호출 |
| `CLAIM_NOT_FOUND` | 404 | 없는 청구 |
| `INVALID_CLAIM_STATUS` | 409 | 지금 상태에서 불가 (심사 전 승인, 심사 두 번, 사망 청구에 판정 등) |
| `CLAIM_CHECK_FAILED` | 409 | 승인 점검 실패 |
| `CONCURRENT_UPDATE` | 409 | 동시 처리 충돌 |

## 이력 (`claim_history`)

| 동작 | from → to |
| --- | --- |
| 심사 시작 | RECEIVED → REVIEWING |
| 승인 | REVIEWING → APPROVED |
| 장해 처리 | APPROVED → CLOSED |
| 거절 | REVIEWING → REJECTED (reason에 사유·메모) |
