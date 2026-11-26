# 시스템 날짜 조회 API

## 요청과 응답

`GET /api/clock`은 로그인한 고객과 직원이 화면 상단에 표시할 업무 날짜를 조회한다.
요청 파라미터와 요청 본문은 없다. 성공하면 HTTP 200을 반환한다.

```json
{
  "businessDate": "2026-11-12"
}
```

- 날짜는 `YYYY-MM-DD` 형식이다. 기존 보험료 계산 응답의 `businessDate`와 이름을 통일한다.
- 날짜의 원본은 `system_clock`의 `id = 1` 행에 있는 `business_date`이다.
- 기존 `ClockService.today()`를 사용하며, 서버 운영체제의 오늘 날짜로 대체하지 않는다.
- 조회 요청은 업무 날짜를 변경하지 않는다. 응답은 `Cache-Control: no-store`로 전송한다.
- 날짜 이동 및 관리자 API는 이번 구현 범위에 포함하지 않는다. `/api/admin/clock`과의 통합은 팀 결정 후 반영한다.

## 인증 모듈 연동

로그인 모듈(`docs/auth.md`)이 연결되어 `Authorization: Bearer {accessToken}` 헤더의 JWT를 검증한다.
토큰이 없거나 만료·위조되면 컨트롤러까지 오지 않고 보안 설정에서 HTTP 401을 반환한다.

```json
{
  "code": "UNAUTHORIZED",
  "message": "로그인이 필요합니다."
}
```

고객과 직원 모두 접근할 수 있으며, ADMIN 전용으로 제한하지 않는다.

## 검증

`ClockApiIntegrationTest`는 Testcontainers PostgreSQL과 Flyway를 사용한다.
인증은 `JwtProvider`로 발급한 실제 JWT를 헤더에 넣어 확인한다.
DB 날짜 조회, 조회 시 데이터 유지, 날짜 변경 즉시 반영, 캐시 방지 헤더,
비로그인 요청 및 검증되지 않은 Bearer 헤더의 401 응답을 확인한다.

```bash
./gradlew test --tests 'kr.tave.minicore.clock.api.ClockApiIntegrationTest'
```
