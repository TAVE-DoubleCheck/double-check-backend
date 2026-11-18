# 서버 및 DB 상태 확인

`GET /actuator/health`는 Spring Boot Actuator의 기본 헬스 체크를 사용한다.
로그인 없이 호출할 수 있으며, 실행 중인 서버와 DataSource의 PostgreSQL 연결을 확인한다.

| 상태 | HTTP | 응답 |
|---|---|---|
| 서버 응답 가능, DB 연결 정상, 기타 헬스 체크 정상 | 200 | `{"status":"UP"}` |
| 실행 중인 서버에서 DB 연결 실패 | 503 | `{"status":"DOWN"}` |
| 서버 미기동 또는 네트워크 단절 | HTTP 응답 없음 또는 프록시 오류 | 앱이 응답할 수 없는 상태 |

DB 접속 정보, SQL 오류, 세부 컴포넌트는 응답에 공개하지 않는다.
HTTP에 노출하는 Actuator 엔드포인트는 `health`로 제한하며, 탐색 페이지도 끈다.
이 응답은 Actuator 표준 형식이므로 업무 API의 `{code, message}` 형식과 다르다.

## Render 설정

웹 서비스의 Settings에서 Health Check Path를 `/actuator/health`로 설정한다.
로컬 주소는 `http://localhost:8080/actuator/health`, 배포 주소는
`https://서비스이름.onrender.com/actuator/health`이다.
이 설정은 코드에만 반영되어 있으며 Render 대시보드 설정은 별도로 적용해야 한다.

## 인증 연동

현재 프로젝트에는 Spring Security/JWT 인증 필터가 없어 로그인 없이 접근할 수 있다.
인증 모듈 도입 후에도 `GET /actuator/health`는 `permitAll`로 허용해야 한다.
다른 Actuator 엔드포인트를 전체 공개하지 않는다.

## 검증

`HealthApiIntegrationTest`는 독립적인 Testcontainers PostgreSQL을 사용한다.
토큰 없이 정상 응답을 확인한 뒤 해당 테스트 컨테이너만 중지하여
실제 DB 연결 실패가 503으로 반영되고, 상세 정보가 노출되지 않는지 확인한다.
로컬 개발 DB나 Render DB는 중지하지 않는다.

```bash
./gradlew test --tests 'kr.tave.minicore.health.HealthApiIntegrationTest'
```
