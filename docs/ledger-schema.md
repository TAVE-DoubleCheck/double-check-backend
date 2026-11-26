# ledger 스키마와 연동 규칙

`V9__ledger_schema.sql`은 V4의 계약·청구서와 V5의 청구·지급건을 연결한다.
금액은 원 단위 `BIGINT`, 업무 날짜는 `DATE`, 실제 기록 시각은 `TIMESTAMPTZ`이다.

| 종류 | 원본 | 계약 | 금액 | 업무 날짜 |
|---|---|---|---|---|
| PREMIUM_IN | premium_bill | contract_id | amount | paid_on |
| CLAIM_OUT | payout | claim.contract_id | amount | paid_on |

## DB 보호

- 입금은 청구서만, 출금은 지급건만 참조한다. 둘 다 있거나 둘 다 없는 조합은 거부한다.
- 원본 참조는 각각 UNIQUE이며, 원본이 없는 참조는 거부한다. 삭제 연쇄(CASCADE)를 사용하지 않는다.
- 기록 전에 원본이 PAID이고 거래 날짜가 있으며 계약·금액·날짜가 일치해야 한다.
- 원본 일치 검사는 DB 트리거에서 수행한다. 검사한 원본은 트랜잭션 종료까지 FOR SHARE로 잠근다.
- ledger UPDATE·DELETE를 거부한다. 잔액 컬럼은 없으며 입금 합계에서 출금 합계를 뺀다.
- 기록 후 청구서의 계약·금액·납부일·상태, 지급건의 청구 연결·금액·지급일·상태 변경을 거부한다.
- 출금 원본의 claim.contract_id 변경도 거부한다. 다른 컬럼 변경과 아직 ledger가 없는 원본 변경은 허용한다.
- 계약별·날짜별 조회를 위한 인덱스를 둔다.

## LedgerService 연동

`LedgerService`는 다음 내부 메서드를 제공한다. 별도의 HTTP API는 없다.

| 메서드 | 원본 | 결과 |
|---|---|---|
| recordPremiumIncome(billId) | premium_bill | PREMIUM_IN 기록 또는 기존 기록 |
| recordClaimPayout(payoutId) | payout + claim | CLAIM_OUT 기록 또는 기존 기록 |

반환값 `LedgerEntry`는 읽기 전용 JPA 매핑이다. ID·계약·종류·금액·업무 날짜·원본 ID·생성 시각을 제공한다.
호출자가 계약·금액·날짜를 전달하거나 ledger를 수정·삭제하는 인터페이스는 없다.

1. 호출자는 청구서 또는 지급건 ID만 전달한다.
2. 호출자가 트랜잭션을 시작하고 원본을 PAID로 변경하면서 paid_on을 기록한다.
3. LedgerService는 원본 ID로 계약·금액·날짜를 조회하여 ledger를 기록한다.
4. 실패하면 완료 상태 변경과 ledger 기록을 함께 롤백한다.

LedgerService는 `Propagation.MANDATORY`로 호출자의 트랜잭션에 참여한다.
트랜잭션 없이 호출하면 `IllegalTransactionStateException`이 발생한다.
JPA 원본 변경을 먼저 flush한 뒤 INSERT SELECT로 원본의 계약·금액·날짜를 복사한다.
같은 원본의 재요청에는 UNIQUE/ON CONFLICT DO NOTHING을 활용하여 기존 기록을 반환한다.
충돌 시 UPDATE하거나 DuplicateKeyException을 잡는 방식을 사용하지 않으므로
추가만 가능한 트리거를 위반하거나 정상 재요청으로 트랜잭션을 실패시키지 않는다.
동시 요청은 선행 트랜잭션 종료를 기다린 뒤 별도 SELECT로 기록을 읽는다.
이 동작은 PostgreSQL 기본 READ COMMITTED 기준이며, 더 높은 격리 수준의 재시도는 호출자가 담당한다.
원본이 없으면 `EmptyResultDataAccessException`, 미완료 등 DB 규칙 위반은 `DataAccessException` 계열로 전파한다.
호출자는 쓰기 가능한 트랜잭션을 시작하고 예외를 전파해 원본 변경과 함께 롤백해야 한다.
DB는 중복 INSERT를 거부하며, 기존 기록을 반환하는 API 동작 자체를 제공하지는 않는다.
금액·날짜 불일치 검사를 서비스에 중복 구현하지 않는다.

현재 납부·지급 API와 자동이체 업무 구현은 별도 작업이다. 고객용 예상 계산에서는 호출하지 않는다.
실제 입출금의 재실행 방지는 납부·지급 서비스도 원본 상태와 잠금으로 처리해야 한다.
ledger 중복 방지가 외부 출금이나 송금 자체의 중복 방지를 대신하지는 않는다.

## 테스트 및 실행 계정

TRUNCATE 차단 트리거는 만들지 않는다. Testcontainers의 테스트 DB 소유자는
`TRUNCATE ledger`로 초기화할 수 있다. 다른 테스트를 일괄 롤백 방식으로 바꾸지 않는다.

V9는 PUBLIC의 UPDATE·DELETE·TRUNCATE 권한을 명시적으로 회수한다.
이 조치만으로 소유자·superuser 또는 이미 별도 권한을 받은 계정의 TRUNCATE를 차단할 수는 없다.
서비스에서는 소유자와 다른 실행 계정을 사용하고 다음 권한만 환경에 맞춰 부여해야 한다.

- ledger: SELECT, INSERT
- ledger_id_seq: USAGE, SELECT
- 원본 테이블: 기능 수행에 필요한 권한
- ledger의 TRUNCATE와 불필요한 DDL 권한은 부여하지 않는다. 상속받는 역할의 권한도 확인한다.

실제 계정명·권한 운영은 배포 환경 작업이다. 비밀번호나 환경별 계정을 마이그레이션에 넣지 않는다.
일반 실행 계정은 트리거 내부에서 조회하는 premium_bill·payout·claim의 SELECT 권한도 필요하다.

```bash
./gradlew test --tests 'kr.tave.minicore.ledger.LedgerSchemaIntegrationTest'
./gradlew test --tests 'kr.tave.minicore.ledger.LedgerServiceIntegrationTest'
```

테스트는 독립적인 PostgreSQL에서 정상 입출금, 참조 조합·원본 일치 검사,
중복·동시 요청, 수정·삭제와 원본 변경 방지, 전체 롤백 및 TRUNCATE 허용을 검증한다.
서비스 테스트는 입출금 원본 조회, 동일 기록 반환, 필수 트랜잭션,
원본 누락·미완료, 호출자와 함께 커밋·롤백, 동시 중복 요청의 실제 DB 잠금 대기를 검증한다.
