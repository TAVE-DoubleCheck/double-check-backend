package kr.tave.minicore.auth.repository;

import kr.tave.minicore.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** email은 User.normalizeEmail()로 소문자로 바꾼 값을 넘긴다 */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
