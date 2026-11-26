package kr.tave.minicore.auth.service;

import kr.tave.minicore.auth.api.LoginRequest;
import kr.tave.minicore.auth.api.LoginResponse;
import kr.tave.minicore.auth.api.SignupRequest;
import kr.tave.minicore.auth.api.UserResponse;
import kr.tave.minicore.auth.domain.User;
import kr.tave.minicore.auth.repository.UserRepository;
import kr.tave.minicore.auth.security.JwtProvider;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.clock.ClockService;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입·로그인·내 정보 */
@Service
public class AuthService {

    private static final String BAD_LOGIN = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final ClockService clock;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtProvider jwtProvider,
                       ClockService clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.clock = clock;
    }

    /** 고객 회원가입. 직원 계정은 여기서 만들 수 없다 (AdminAccountInitializer) */
    @Transactional
    public UserResponse signup(SignupRequest req) {
        String email = User.normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "이미 가입된 이메일입니다.");
        }
        // 미래 생년월일 거절. 기준은 업무 날짜 (보험료 계산과 같은 기준)
        if (req.birthDate().isAfter(clock.today())) {
            throw new ApiException(ErrorCode.BIRTH_DATE_IN_FUTURE, "생년월일이 오늘(업무 날짜) 이후입니다.");
        }
        User user = User.customer(email, passwordEncoder.encode(req.password()), req.name().trim(),
                req.birthDate(), req.sex());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 같은 이메일로 동시에 가입하면 위 검사를 둘 다 통과할 수 있다 → DB 유일 인덱스가 막은 경우
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "이미 가입된 이메일입니다.");
        }
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        User user = users.findByEmail(User.normalizeEmail(req.email()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, BAD_LOGIN));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, BAD_LOGIN);
        }
        String token = jwtProvider.issue(new LoginUser(user.getId(), user.getEmail(), user.getRole()));
        return LoginResponse.bearer(token, jwtProvider.expiresIn().toSeconds(), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(LoginUser me) {
        // 토큰은 유효하지만 회원이 지워진 경우 (데모 초기화 등) → 다시 로그인하도록 401
        return users.findById(me.id())
                .map(UserResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다."));
    }
}
