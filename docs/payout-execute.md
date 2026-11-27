# 사망보험금 지급 실행 (BE C)

소유: BE C. 코드는 `kr.tave.minicore.claim` (`PayoutService`, `AdminPayoutController`).
앞 단계: 사망 청구 승인 → 지급건 PENDING 생성 (`docs/claim-review.md`).

## API

`POST /api/admin/payouts/{payoutId}/execute` (직원 전용, 본문 없음)

```json
200
{ "payoutId": 5, "claimId": 40, "amount": 10000000, "paidOn": "2026-07-01", "ledgerEntryId": 128, "contractStatus": "TERMINATED" }
```

## 순서 (한 트랜잭션, 하나라도 실패하면 전부 취소)

| # | 하는 일 | 누구 코드 |
| --- | --- | --- |
| 1 | 지급건 행 잠금 `SELECT … FOR UPDATE` | BE C `PayoutRepository.findByIdForUpdate` |
| 2 | PENDING 확인, 아니면 409 `PAYOUT_ALREADY_PAID` | BE C |
| 3 | 지급건 PAID (`paid_on` = 업무 날짜, `paid_by` = 로그인 직원), 청구 APPROVED → PAID | BE C |
| 4 | 장부 출금 `LedgerService.recordClaimPayout(payoutId)` | BE D |
| 5 | 계약 TERMINATED `ContractStatusService.terminate(contractId, paid_on, …)` | BE B |
| 6 | `claim_history` APPROVED → PAID | BE C |

- 3 → 4 순서 고정: V9 트리거는 지급건이 PAID이고 금액·지급일·계약이 일치해야 장부를 받는다. 장부를 먼저 쓰면 `ledger source mismatch`로 거부됨 (테스트로 확인)
- 장부에는 지급 번호만 넘긴다. 금액·날짜·계약은 장부가 `payout`에서 직접 읽음 (금액 출처 한 곳)
- `docs/contract-status.md`의 지급 예시는 장부 → PAID 순서로 적혀 있으나 V9 기준으로는 PAID → 장부가 맞음

## 중복 지급 방지

| 겹 | 장치 | 막는 것 | 단계 |
| --- | --- | --- | --- |
| 1 | `payout.claim_id` UNIQUE | 청구 1건에 지급건 2개 | 승인 (#35) |
| 2 | 지급건 행 잠금 + PENDING 확인 | 직원 두 명 동시 지급 → 늦은 쪽은 기다렸다가 PAID를 보고 409 | 지급 (#38) |
| 3 | `ledger.payout_id` UNIQUE | 장부 출금 두 번 | 장부 (V9, BE D) |

- 행 잠금을 빼면 늦은 쪽이 청구 `@Version` 충돌로 실패함 (돈은 한 번이지만 응답이 `PAYOUT_ALREADY_PAID`가 아님). 잠금으로 명세대로 409 안내

## 오류

| code | HTTP | 상황 |
| --- | --- | --- |
| `PAYOUT_NOT_FOUND` | 404 | 없는 지급건 |
| `PAYOUT_ALREADY_PAID` | 409 | 이미 지급됨. message "다른 직원이 이미 지급했습니다" |
| `INVALID_CONTRACT_STATUS` | 409 | 계약이 종료할 수 없는 상태 (예: 사망 후 실효) → 전체 취소 |
| `FORBIDDEN` | 403 | 고객이 호출 |

## 결과 확인 (테스트)

| 대상 | 값 |
| --- | --- |
| `payout` | PAID, `paid_on` = 업무 날짜, `paid_by` = 직원 |
| `claim` | PAID, 이력 RECEIVED → REVIEWING → APPROVED → PAID |
| `ledger` | CLAIM_OUT 1줄, 금액 = 지급액 = 가입금액, `occurred_on` = 지급일 |
| `contract` | TERMINATED, 계약 이력 1줄 (ADMIN) |
