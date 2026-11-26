package kr.tave.minicore.auth.service;

import kr.tave.minicore.auth.domain.User;
import kr.tave.minicore.auth.repository.UserRepository;
import kr.tave.minicore.auth.security.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 기동 시 직원(ADMIN) 계정이 없으면 1개 만든다 (V4 주석: 직원 계정은 회원가입 없이 생성).
 * SQL seed 대신 코드로 만드는 이유: 비밀번호를 BCrypt로 바꿔 넣어야 하고, 비밀번호를 .env로 바꿀 수 있게 하려고.
 * 이미 같은 이메일이 있으면 아무것도 하지 않는다 (비밀번호도 덮어쓰지 않음).
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public AdminAccountInitializer(UserRepository users, PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AuthProperties.Admin admin = properties.admin();
        if (admin == null || !admin.enabled()) {
            return;
        }
        String email = User.normalizeEmail(admin.email());
        if (users.existsByEmail(email)) {
            return;
        }
        users.save(User.admin(email, passwordEncoder.encode(admin.password()), admin.name()));
        log.info("직원 계정 생성: {}", email);
    }
}
