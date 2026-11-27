# 납부·자동이체·매일 작업 (BE B)

소유: BE B. 코드는 `kr.tave.minicore.contract` (`PremiumPaymentService`, `BillController`, `batch/*Step`).

## 날짜 규칙 (`PremiumSchedule`, 엔진과 시뮬레이터가 같이 쓰는 함수)

| 항목 | 규칙 | 예 |
| --- | --- | --- |
| n회차 납입기일 | 계약일 + (n − 1)개월. 계약일 = 1회차를 낸 날 (약관 제25조①) | 3/15 계약 → 4/15, 5/15 … / 1/31 계약 → 2/28, 3/31, 4/30 |
| 유예 마감일 | 납입기일이 속한 달의 **다다음 달 말일** (약관 제28조①) | 3/15 → 5/31, 12/10 → 다음 해 2/28 |
| 실효일 | 유예 마감일 **다음 날** | 3/15 미납 → 6/1 실효 |
| 1회차 대기 마감 | 신청일 + 30일 (`contract.first-premium-wait-days`). 다음 날 불성립(VOID) | 3/10 신청 → 4/9 마감 → 4/10 불성립 |

## 납부 API

`POST /api/bills/{billId}/pay` (로그인 고객, 본인 청구서만). 낸 날 = 오늘 업무 날짜.

한 트랜잭션에서:
1. 청구서 `PAID` (paid_on = 오늘, paid_channel = `ONLINE`)
2. 장부 입금 1줄 (`LedgerService.recordPremiumIncome`)
3. 1회차면 계약 성립 `PENDING → ACTIVE` (계약일·납입일 확정), 마지막 회차면 `ACTIVE → PAID_UP`

응답:

```json
{
  "bill": { "billId": 1, "installmentNo": 1, "dueDate": "2026-03-10", "graceEndDate": "2026-04-09",
            "amount": 165350, "status": "PAID", "paidOn": "2026-03-15", "paidChannel": "ONLINE" },
  "contractId": 1,
  "contractStatus": "ACTIVE",
  "ledgerEntryId": 1
}
```

| HTTP | code | 언제 |
| --- | --- | --- |
| 403 | `FORBIDDEN` | 직원 계정으로 납부 |
| 404 | `BILL_NOT_FOUND` | 없는 청구서·남의 청구서 |
| 409 | `BILL_ALREADY_PAID` | 이미 낸 청구서 (두 번째 클릭) |
| 409 | `BILL_NOT_PAYABLE` | 앞 회차 미납 / 유예 마감(1회차는 대기 마감) 지남 / 계약이 청약·유지가 아님 |
| 409 | `CONCURRENT_UPDATE` | 같은 청구서를 **동시에** 두 번 (`premium_bill.lock_version`, 공통 처리 #31) |

장부는 청구서당 1줄 UNIQUE라 어떤 경우에도 이중 입금 기록이 생기지 않는다.

## 자동이체 설정 API

`PUT /api/contracts/{contractId}/auto-debit` `{ "enabled": true }` (본인 계약, 청약·유지 중만. 그 외 409 `INVALID_CONTRACT_STATUS`)

## 매일 작업 단계 (`DailyWorkStep` 구현)

`DailyWorkService.advanceTo`가 하루마다 아래 순서로 부른다. 모두 그날 트랜잭션에 합류(`MANDATORY`)하고 실패는 그대로 던진다.

| 단계 | 클래스 | 하는 일 |
| --- | --- | --- |
| BILLING | `BillingStep` | 유지(ACTIVE) 계약의 다음 회차 납입기일이 오늘 이전이면 청구서 발행. 마지막 회차 이후·납입완료·면제·실효는 발행 안 함 |
| AUTO_DEBIT | `AutoDebitStep` | 자동이체가 켜진 유지 계약의 미납 청구서(기일 도래, 유예 마감 전)를 회차 순으로 납부. 화면 납부와 같은 서비스 (`paid_channel = AUTO_DEBIT`, 이력 actor = BATCH) |
| LAPSE | `LapseStep` | ① 납입기일 지난 2회차~ 청구서 → `OVERDUE`(미납 표시) ② 유예 마감 지난 미납이 있는 유지 계약 → 실효, 1회차 대기 마감 지난 청약 → 불성립 |
| MATURITY | `MaturityStep` | 1단계는 종신만 판매해 만기 대상 없음. 비종신 계약이 있으면 그날 작업을 실패시킨다 (놓치지 않도록) |
| RECONCILIATION | (경진 님) | 장부 대사. **이 단계가 등록돼야 날짜 이동이 실행된다** |

설정 `daily.contract-steps.enabled`(기본 true)로 위 네 빈을 끌 수 있다. 뼈대 테스트(`DailyWorkIntegrationTest`)만 false로 두고 자기 테스트 빈을 쓴다.

## 정해야 할 것 (팀 확인)

| 항목 | 지금 처리 |
| --- | --- |
| 1회차 대기 기간 | 30일 |
| 청구서 발행 시점 | 납입기일 당일 발행 (미리 발행 안 함 → 기일 전에 미리 낼 수 없음) |
| 납부 API와 날짜 이동 동시 실행 | 막지 않음 (청구서·계약 @Version과 장부 UNIQUE로 이중 처리만 방지) |
| 자동이체 실패 | 없음 (실제 계좌 연동 없음) |
