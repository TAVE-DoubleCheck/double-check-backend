# 로그인 (BE B → 전체)

소유: BE B. 코드는 `kr.tave.minicore.auth` 패키지.

## 방식

- **JWT**. 로그인하면 `accessToken`을 주고, 프론트는 요청마다 `Authorization: Bearer {accessToken}` 헤더를 붙인다.
- 서버는 로그인 상태를 저장하지 않는다 (세션 없음). 로그아웃은 프론트가 토큰을 지우는 것으로 끝난다.
- 비밀번호는 BCrypt 해시만 `users.password_hash`에 저장한다.
- 토큰 유효 시간 2시간 (`auth.jwt.expires-in`). **실제 시각 기준**이다. 업무 날짜(`system_clock`)를 10년 뒤로 옮겨도 로그인은 풀리지 않는다.

## API

| Method | Endpoint | 로그인 | 성공 | 실패 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/signup` | 불필요 | 201 회원 정보 | 400 `VALIDATION_ERROR`·`BIRTH_DATE_IN_FUTURE`, 409 `EMAIL_ALREADY_EXISTS` |
| POST | `/api/auth/login` | 불필요 | 200 토큰 + 회원 정보 | 401 `INVALID_CREDENTIALS` |
| POST | `/api/auth/logout` | 필요 | 204 | 401 |
| GET | `/api/auth/me` | 필요 | 200 회원 정보 | 401 |

회원가입 요청:

```json
{ "email": "kim@test.com", "password": "password-1234", "name": "김가입", "birthDate": "1996-10-01", "sex": "M" }
```

- 비밀번호 8~64자. 이메일은 소문자로 바꿔 저장하고 대소문자만 다른 중복도 막는다
- `birthDate`·`sex`는 보험료 계산 원천이라 가입 후 바꾸지 않는다. 가입 API(BE B)는 요청값이 아니라 이 값을 쓴다

로그인 응답:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 7200,
  "user": { "id": 3, "email": "kim@test.com", "name": "김가입", "birthDate": "1996-10-01", "sex": "M", "role": "CUSTOMER", "emailOptIn": false }
}
```

## 주소별 권한 (`SecurityConfig`)

| 구분 | 주소 |
| --- | --- |
| 공개 | `POST /api/auth/signup`, `POST /api/auth/login`, `GET /api/products/**`, Swagger(`/swagger-ui/**`, `/v3/api-docs/**`), `GET /actuator/health`, `GET /` |
| 직원(ADMIN)만 | `/api/admin/**` |
| 로그인 필요 (고객·직원) | 나머지 전부 |

- 토큰 없음·만료·위조 → `401 UNAUTHORIZED`
- 고객이 `/api/admin/**` 호출 → `403 FORBIDDEN`
- 응답 형식은 다른 오류와 같은 `{ "code", "message" }`

**새 API를 만들 때**: 직원 기능이면 주소를 `/api/admin/...`으로 시작하면 자동으로 직원만 부를 수 있다.
로그인 없이 열어야 하는 주소가 생기면 `SecurityConfig`의 `PUBLIC_GET`/`PUBLIC_POST`에 추가하고 BE B에게 알려 주세요.

## 컨트롤러에서 로그인한 사람 꺼내기

```java
import kr.tave.minicore.auth.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@GetMapping("/api/claims")
public List<ClaimResponse> myClaims(@AuthenticationPrincipal LoginUser me) {
    return claimQueryService.findByUser(me.id());   // me.id() = users.id
}

@PostMapping("/api/admin/claims/{id}/approve")
public ClaimResponse approve(@PathVariable long id, @AuthenticationPrincipal LoginUser staff) {
    return claimService.approve(id, staff.id());     // 직원 id → 이력 actor_id 등에 사용
}
```

- `LoginUser` = `id`, `email`, `role` (`CUSTOMER` / `ADMIN`). 토큰 안의 값이라 DB를 다시 읽지 않는다
- 이름·생년월일이 필요하면 `UserRepository.findById(me.id())`
- **"내 계약이 맞는지"는 각 서비스가 확인한다.** 남의 계약 id를 넣으면 `404 CONTRACT_NOT_FOUND`로 답한다 (있는지조차 알려 주지 않기 위해, 이슈 #8)

## 직원 계정

회원가입 API로는 고객만 만든다. 직원은 앱 기동 시 `AdminAccountInitializer`가 없으면 1개 만든다.

| 설정 | 기본값 | `.env`로 바꾸기 |
| --- | --- | --- |
| 이메일 | `admin@doublecheck.local` | `ADMIN_EMAIL` |
| 비밀번호 | `admin1234!` | `ADMIN_PASSWORD` |

이미 같은 이메일이 있으면 아무것도 하지 않는다 (비밀번호도 덮어쓰지 않음).

## Swagger에서 시험하기

1. `POST /api/auth/login` 실행 → 응답의 `accessToken` 복사
2. 오른쪽 위 **Authorize** → 토큰만 붙여 넣기 (`Bearer`는 자동)
3. 이후 Swagger 요청에 헤더가 자동으로 붙는다

## 테스트에서 로그인한 상태 만들기

```java
@Autowired JwtProvider jwtProvider;

String token = jwtProvider.issue(new LoginUser(userId, "a@test.local", Role.CUSTOMER));
mvc.perform(get("/api/...").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
```

토큰 검증은 DB를 보지 않으므로 `userId`는 테스트에서 만든 회원 id를 넣으면 된다.

## 설정 (`application.yml`의 `auth.*`)

| 키 | 기본 | 비고 |
| --- | --- | --- |
| `auth.jwt.secret` | 개발용 고정값 | `.env`의 `JWT_SECRET`. 32바이트 이상, 발표 서버는 반드시 변경 |
| `auth.jwt.expires-in` | `2h` | |
| `auth.cors.allowed-origins` | `http://localhost:5173,http://localhost:3000` | 프론트 개발 서버 주소. `.env`의 `CORS_ALLOWED_ORIGINS` |

## 1단계에서 하지 않는 것

- 토큰 재발급(refresh token), 서버 측 로그아웃(토큰 폐기 목록)
- 비밀번호 변경·찾기, 회원 탈퇴
- 마이페이지 수정(알림 동의 `email_opt_in` 변경)은 다음 작업
