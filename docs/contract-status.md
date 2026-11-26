# 계약 상태 변경 (BE B → BE C·D)

소유: BE B (계약). 코드는 `kr.tave.minicore.contract` 패키지.

## 규칙

계약 상태는 **`ContractStatusService`로만** 바꾼다. `contract.status`를 SQL이나 엔티티로 직접 고치지 않는다.
서비스가 상태 변경과 이력(`contract_history`) 1줄을 같은 트랜잭션에서 저장한다.
호출한 쪽에 트랜잭션이 있으면 그 안에 합류하므로, 지급·장부 기록과 함께 저장되거나 함께 취소된다.

| 지금 상태 | 다음 상태 | 언제 | 부르는 메서드 | 누가 |
| --- | --- | --- | --- | --- |
| PENDING | ACTIVE | 1회차 납부 | `activate(contractId, paidOn, actor)` | BE B 납부 / 자동이체 |
| PENDING | VOID | 1회차 미납 | `voidContract(contractId, on)` | 일 배치 (기준일 미정) |
| ACTIVE | PAID_UP | 마지막 회차 납부 | `markPaidUp(contractId, on, actor)` | BE B 납부 / 자동이체 |
| ACTIVE | LAPSED | 유예 마감일까지 미납 (약관 제28조①) | `lapse(contractId, on, premiumBillId)` | 일 배치 |
| ACTIVE | PREMIUM_WAIVED | 장해 청구 승인 | `waivePremium(contractId, on, staffId, claimId)` | **BE C** |
| ACTIVE · PAID_UP · PREMIUM_WAIVED | TERMINATED | 사망보험금 지급 | `terminate(contractId, on, staffId, payoutId)` | **BE C** |

- 표에 없는 변경은 `409 INVALID_CONTRACT_STATUS`. 상태·이력 모두 바뀌지 않는다 (예: 실효 계약을 종료로)
- 없는 계약은 `404 CONTRACT_NOT_FOUND`
- LAPSED·TERMINATED·VOID는 1단계에서 끝 상태 (실효 부활은 2단계 후보)

## 날짜(on)는 호출한 쪽이 넘긴다

| 호출 | 넘길 날짜 |
| --- | --- |
| API (납부, 승인, 지급) | `clockService.today()` |
| 장해 승인 | `claim.decided_on`과 같은 값 (이날 이후 청구서부터 면제, 회의 B-5) |
| 사망 지급 | `payout.paid_on`과 같은 값 |
| 일 배치 | 처리 중인 날짜 (`run(date)`의 date). 실효는 유예 마감일 다음 날 |

서비스가 시계를 직접 읽지 않는 이유: 배치가 처리 중인 날과 시계 값이 다를 수 있어서.

## 사용 예 (BE C 지급 실행)

```java
@Transactional
public PayoutResult execute(long payoutId, long staffId) {
    Payout payout = payoutRepository.findByIdForUpdate(payoutId).orElseThrow(...);
    ...
    LocalDate today = clockService.today();
    LedgerEntry entry = ledgerService.recordClaimPayout(payout.getId());
    payout.markPaid(today, staffId);
    claimService.markPaid(...);
    contractStatusService.terminate(contractId, today, staffId, payout.getId());   // ← 여기
    ...
}
```

## 사고일 당시 상태 (청구 자동 점검 1번)

```java
Optional<ContractStatus> s = contractStatusService.statusOn(contractId, accidentDate);
boolean covered = s.map(ContractStatus::isCovered).orElse(false);
```

- 그날 상태가 바뀌었으면 **바뀐 뒤** 상태를 준다. 예: 6/1 실효 → 5/31 사고는 ACTIVE(보장), 6/1 사고는 LAPSED
- 가입 신청 전 날짜면 빈 값 → 보장 아님
- `isCovered()` = ACTIVE · PAID_UP · PREMIUM_WAIVED (청약 PENDING은 첫 보험료 전이라 보장 전)

## 동시에 바뀔 때

`contract.lock_version`(@Version) 때문에 같은 계약을 두 곳에서 동시에 바꾸면 늦은 쪽이
`OptimisticLockingFailureException`으로 실패한다. 이 예외를 409로 바꾸는 공통 처리는 이슈 #8(공통 충돌 코드)에서 정한다.
