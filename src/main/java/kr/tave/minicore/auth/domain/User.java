package kr.tave.minicore.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.tave.minicore.product.domain.Sex;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 회원 1명 (V4 users). 고객과 직원이 같은 테이블을 쓰고 role로 구분한다.
 * 비밀번호는 BCrypt 해시만 저장한다. 이메일은 소문자로 바꿔 저장한다 (DB도 lower(email) 유일 인덱스).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "name", nullable = false)
    private String name;

    /** 보험나이 계산 원천. 가입 API는 요청값이 아니라 이 값을 쓴다 */
    @Column(name = "birth_date", nullable = false, updatable = false)
    private LocalDate birthDate;

    /** 'M' / 'W' (premium_rate.sex와 같은 값) */
    @Column(name = "sex", nullable = false, updatable = false)
    private Character sex;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false)
    private Role role;

    @Column(name = "email_opt_in", nullable = false)
    private boolean emailOptIn;

    @Column(name = "email_opt_in_changed_at")
    private Instant emailOptInChangedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    private User(String email, String passwordHash, String name, LocalDate birthDate, Sex sex, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.name = name;
        this.birthDate = birthDate;
        this.sex = sex.code();
        this.role = role;
    }

    /** 회원가입한 고객. passwordHash는 이미 BCrypt로 바꾼 값 */
    public static User customer(String email, String passwordHash, String name, LocalDate birthDate, Sex sex) {
        return new User(email, passwordHash, name, birthDate, sex, Role.CUSTOMER);
    }

    /** 직원. 생년월일·성별은 업무에 쓰지 않지만 컬럼이 NOT NULL이라 자리값을 넣는다 */
    public static User admin(String email, String passwordHash, String name) {
        return new User(email, passwordHash, name, LocalDate.of(1990, 1, 1), Sex.M, Role.ADMIN);
    }

    /** 이메일 비교·저장 기준: 앞뒤 공백 제거 + 소문자 */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public Sex getSex() {
        return Sex.valueOf(String.valueOf(sex));
    }

    public Role getRole() {
        return role;
    }

    public boolean isEmailOptIn() {
        return emailOptIn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
