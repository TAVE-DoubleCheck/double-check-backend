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

## LedgerService 연동 기준과 이번 작업 범위

이번 작업은 스키마·DB 규칙·통합 테스트이다. LedgerService 및 중복 요청 응답은 아직 구현하지 않는다.

1. 호출자는 청구서 또는 지급건 ID만 전달한다.
2. 호출자가 트랜잭션을 시작하고 원본을 PAID로 변경하면서 paid_on을 기록한다.
3. LedgerService는 원본 ID로 계약·금액·날짜를 조회하여 ledger를 기록한다.
4. 실패하면 완료 상태 변경과 ledger 기록을 함께 롤백한다.

LedgerService는 `Propagation.MANDATORY`로 호출자의 트랜잭션에 참여하도록 구현한다.
같은 원본의 재요청에는 UNIQUE/ON CONFLICT를 활용하여 기존 기록을 반환한다.
DB는 중복 INSERT를 거부하며, 기존 기록을 반환하는 API 동작 자체를 제공하지는 않는다.
금액·날짜 불일치 검사를 서비스에 중복 구현하지 않는다.

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
```

테스트는 독립적인 PostgreSQL에서 정상 입출금, 참조 조합·원본 일치 검사,
중복·동시 요청, 수정·삭제와 원본 변경 방지, 전체 롤백 및 TRUNCATE 허용을 검증한다.
