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

이 브랜치에는 아직 로그인 및 JWT 검증 모듈이 없다. API는 Servlet 요청의 인증된
`Principal`이 있는 경우에만 날짜를 반환한다. 인증 정보가 없으면 HTTP 401을 반환한다.

```json
{
  "code": "UNAUTHORIZED",
  "message": "로그인이 필요합니다."
}
```

`Authorization` 헤더가 있다는 이유만으로 접근을 허용하지 않는다. 인증 모듈 담당자는
토큰 검증 후 Spring Security 인증 정보를 설정하여 MVC의 `Principal`로 전달해야 한다.
고객과 직원 모두 접근할 수 있어야 하며, ADMIN 전용으로 제한하지 않는다.
인증 모듈을 연결하기 전에는 실제 HTTP 요청에 Bearer 토큰을 넣어도 401이 반환된다.

## 검증

`ClockApiIntegrationTest`는 Testcontainers PostgreSQL과 Flyway를 사용한다.
인증된 사용자 정보는 MockMvc의 `principal`로 주입하며, 실제 JWT 검증 테스트는 아니다.
DB 날짜 조회, 조회 시 데이터 유지, 날짜 변경 즉시 반영, 캐시 방지 헤더,
비로그인 요청 및 검증되지 않은 Bearer 헤더의 401 응답을 확인한다.

```bash
./gradlew test --tests 'kr.tave.minicore.clock.api.ClockApiIntegrationTest'
```
