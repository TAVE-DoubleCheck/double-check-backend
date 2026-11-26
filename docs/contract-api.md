# 계약 API: 가입 신청·조회 (BE B)

소유: BE B. 코드는 `kr.tave.minicore.contract` (`ContractApplyService`, `ContractController`).

## 흐름

```
[화면] 보험료 계산 GET /api/products/{code}/quote  →  versionId, monthlyPremium 표시
   ↓ 고객이 "가입하기"
[서버] POST /api/contracts  (로그인 고객만)
   1. 회원 정보(users.birth_date, sex)로 보험료를 다시 계산  ← 화면 값을 믿지 않음
   2. 화면에서 본 versionId·monthlyPremium과 다르면 409 QUOTE_CHANGED (아무것도 저장 안 함)
   3. 한 트랜잭션에서 저장
        contract          PENDING(청약), 계산 결과 스냅샷 그대로
        contract_history  NULL → PENDING "가입 신청" (CUSTOMER, 고객 id)
        premium_bill      1회차, 납입기일 = 신청일, 마감 = 신청일 + 30일
```

계약일·매달 납입일은 비워 둔다. **1회차를 낸 날**이 계약일 = 보장 시작일이 된다 (약관 제25조①, 납부 API에서 `activate`).

## 요청

```json
{
  "productCode": "4008400",
  "insureTermCode": "99",
  "amount": 50000000,
  "payTermYears": 10,
  "beneficiaryName": "이수익",
  "beneficiaryRelation": "SPOUSE",
  "expectedVersionId": 12,
  "expectedMonthlyPremium": 165350
}
```

- 생년월일·성별은 **받지 않는다.** 로그인한 회원 정보를 쓴다 (보험료 계산 원천을 바꿔 치기 못 하게)
- 납입기간: `payTermYears`(숫자) 또는 `payTermPlan`(`FIXED_YEARS_10` / `TO_AGE_80`) 중 **정확히 하나**
- `beneficiaryRelation`: `SPOUSE` / `CHILD` / `PARENT` / `SIBLING`
- `expectedVersionId`·`expectedMonthlyPremium`: 화면에 보여 준 보험료 계산 응답의 값 그대로

## 응답 (201)

```json
{
  "contractId": 1,
  "contractNo": "C-000001",
  "status": "PENDING",
  "productCode": "4008400",
  "versionId": 12,
  "insuranceAge": 30,
  "insuredAmount": 50000000,
  "monthlyPremium": 165350,
  "payTermPlan": { "type": "FIXED_YEARS", "years": 10 },
  "payTermYears": 10,
  "appliedOn": "2026-10-26",
  "firstBill": { "billId": 1, "installmentNo": 1, "amount": 165350, "dueDate": "2026-10-26", "payBy": "2026-11-25" }
}
```

화면 문구 예: "1회차 보험료 165,350원을 **11월 25일까지** 내시면 계약이 성립하고, 낸 날부터 보장이 시작됩니다."

## 오류

| HTTP | code | 언제 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 필수값 누락, 납입기간 둘 다/둘 다 없음, 관계 값 오류 |
| 400 | `AMOUNT_NOT_UNIT_MULTIPLE` · `AMOUNT_OUT_OF_RANGE` | 가입금액 (보험료 계산과 동일) |
| 401 | `UNAUTHORIZED` | 로그인 안 함 |
| 403 | `FORBIDDEN` | 직원 계정으로 가입 시도 |
| 404 | `PRODUCT_NOT_FOUND` | 상품코드 없음 |
| 409 | `QUOTE_CHANGED` | 화면에서 본 상품 버전·월 보험료 ≠ 서버 재계산 (그사이 버전 변경, 업무 날짜가 넘어가 보험나이 변경 등). 화면은 보험료를 다시 계산해 보여 주고 다시 확인받는다 |
| 409 | `PRODUCT_NOT_SUBSCRIBABLE` · `RATE_NOT_FOUND` · `NO_EFFECTIVE_VERSION` | 보험료 계산과 동일 |

## 정해야 할 것 (팀 확인)

| 항목 | 지금 처리 | 설정 |
| --- | --- | --- |
| 1회차를 기다리는 기간 | 신청일 + 30일. 지나면 불성립(VOID) 처리 예정 (매일 작업 LAPSE 단계) | `contract.first-premium-wait-days` |
| 같은 버튼 두 번 클릭 | 계약 2건 생성 가능 (중복 방지 키 없음) | 화면에서 버튼 잠금. 필요하면 2단계에 `Idempotency-Key` |
| 자동이체 신청 | 가입 시 받지 않음 (기본 꺼짐). 별도 API로 설정 예정 | — |

## 조회 API

| Method | Endpoint | 내용 |
| --- | --- | --- |
| GET | `/api/contracts` | 내 계약 목록 (최근 신청 순) |
| GET | `/api/contracts/{id}` | 상세 + `nextBill`(아직 안 낸 가장 이른 청구서, 없으면 null) |
| GET | `/api/contracts/{id}/bills` | 청구서 전체 |
| GET | `/api/contracts/{id}/history` | 상태 이력. 직원 id는 내보내지 않음 (`actorType`만) |

- 고객은 자기 계약만. 남의 계약 id는 **있어도 404** `CONTRACT_NOT_FOUND` (있다는 사실도 알려 주지 않기, 이슈 #8)
- 직원(ADMIN)은 청구 심사 등을 위해 모든 계약의 상세·청구서·이력 조회 가능. 목록은 본인 계약만이라 직원은 빈 목록
- 납입 진행: `paidInstallments`(납부 완료 청구서 수) / `totalInstallments`(납입년수 × 12)
- 상품명은 계약이 가리키는 상품 버전(`version_id`)으로 찾는다 (가입 시점 버전 고정)

상세 응답 예 (1회차 납부 후):

```json
{
  "contractId": 1, "contractNo": "C-000001", "productCode": "4008400", "productName": "(product.short_name 값)",
  "versionId": 12, "status": "ACTIVE", "insuranceAge": 30, "sex": "M",
  "insuredAmount": 50000000, "monthlyPremium": 165350,
  "payTermPlan": { "type": "FIXED_YEARS", "years": 10 }, "payTermYears": 10, "insureTermCode": "99",
  "appliedOn": "2026-03-10", "contractDate": "2026-03-15", "billingDay": 15, "autoDebit": false,
  "beneficiaryName": "이수익", "beneficiaryRelation": "SPOUSE",
  "paidInstallments": 1, "totalInstallments": 120,
  "nextBill": { "billId": 2, "installmentNo": 2, "dueDate": "2026-04-15", "graceEndDate": "2026-06-30",
                "amount": 165350, "status": "ISSUED", "paidOn": null, "paidChannel": null }
}
```
