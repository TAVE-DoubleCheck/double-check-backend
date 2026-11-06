# QuoteService 계약 (BE B 가입 처리용)

> 소유: BE A (상품). 질문은 `kr.tave.minicore.product.quote` 패키지 참고.

## 호출

```java
QuoteResult quote = quoteService.quote(
        productCode,     // 예: "4008400"
        birthDate,       // 가입자 생년월일 (LocalDate)
        Sex.M,           // M | W (DB premium_rate.sex와 동일)
        payTermYears,    // 납입기간 (년). '전기납'은 하나로OK 8종에 없음
        insureTermCode,  // "99"=종신. 1단계 가입 상품은 종신 4종이므로 항상 "99"
        amount);         // 가입금액 (원). 1,000만 원 배수, 1,000만~1억
```

- 읽기 전용 트랜잭션. 쓰기 없음. 같은 입력 + 같은 업무 날짜면 항상 같은 결과(결정적).
- 계약일 = `ClockService.today()`(= `system_clock.business_date`)로 내부에서 정한다. 호출자가 날짜를 넘기지 않는다.
- 실패는 전부 `ApiException`(ErrorCode: `PRODUCT_NOT_FOUND`, `PRODUCT_NOT_SUBSCRIBABLE`,
  `NO_EFFECTIVE_VERSION`, `AMOUNT_NOT_UNIT_MULTIPLE`, `AMOUNT_OUT_OF_RANGE`,
  `BIRTH_DATE_IN_FUTURE`, `RATE_NOT_FOUND`). 가입 처리에서 그대로 전파하면 공통 핸들러가 응답으로 바꾼다.

## QuoteResult 필드

| 필드 | 설명 | 계약에 저장? |
|---|---|---|
| `productCode` | 상품코드 | ✔ |
| `versionId` | **가입 시점에 고정할 상품 버전 id** (업무 날짜 기준 유효 버전) | ✔ **필수** |
| `effectiveFrom` | 그 버전의 적용 시작일 | 참고용 |
| `insuranceAge` | 보험나이 (약관 제23조②, 계약일 기준) | ✔ |
| `sex` | 성별 | ✔ |
| `payTermYears` | 납입기간 (년) | ✔ |
| `isFullTermPay` | 원본이 전기납이었는지 (8종에선 항상 false) | ✔ |
| `insureTermCode` | 보험기간 코드 원본 ("99"=종신) | ✔ |
| `amount` | 가입금액 (원) | ✔ |
| `unitAmountWon` | 기준단위금액 (1,000만 원) | 참고용 |
| `premiumWonPerUnit` | 기준단위당 월 보험료 (요율표 원본) | 참고용 |
| `monthlyPremium` | **월 보험료 (원)** = premiumWonPerUnit × (amount ÷ unitAmountWon) | ✔ **필수** |
| `businessDate` | 계산에 쓴 업무 날짜 | ✔ (계약일) |
| `assumptions` | 단순화·가정 목록 (화면 표시용) | — |

## 반드시 지킬 것

**가입 시 `versionId`·`monthlyPremium`·`insuranceAge`를 그대로 계약에 저장하고 다시 계산하지 말 것.**

- 이유: 요율·상품 버전이 나중에 바뀌어도 기존 계약이 변하면 안 된다 (가입 시점 상품 버전 고정).
  재계산하면 "미리보기 금액 ≠ 청구 금액"이 생길 수 있다.
- 가입 처리 흐름: 같은 트랜잭션 안에서 `quote(...)` 호출 → 결과를 계약 행에 복사 → 저장.
  FE가 보낸 미리보기 값을 믿지 말고 서버에서 `quote(...)`를 다시 불러 그 결과를 저장한다
  (같은 업무 날짜면 미리보기와 같은 값이 나온다).

## 현재 단순화 (바뀌면 이 파일과 QuoteService.ASSUMPTIONS 갱신)

- 월납 기준 (납입주기 필드가 요율 API에 없음 — 확인 필요)
- 고액계약 할인 미반영 (기준·비율이 사업방법서 영역 — 확인 필요)
- 가입금액 1,000만 원 배수, 1,000만~1억 (설정 `quote.amount-min-units`/`amount-max-units`)
- 가입 가능 상품 = 종신 4종 (설정 `product.subscribable-codes`, 설계 리뷰 안건 2)
