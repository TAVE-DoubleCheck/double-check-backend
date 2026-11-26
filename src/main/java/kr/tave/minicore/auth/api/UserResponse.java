package kr.tave.minicore.auth.api;

import kr.tave.minicore.auth.domain.Role;
import kr.tave.minicore.auth.domain.User;
import kr.tave.minicore.product.domain.Sex;

import java.time.LocalDate;

/** 회원 정보 응답. 비밀번호 해시는 절대 넣지 않는다 */
public record UserResponse(
        long id,
        String email,
        String name,
        LocalDate birthDate,
        Sex sex,
        Role role,
        boolean emailOptIn
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.getBirthDate(), u.getSex(), u.getRole(),
                u.isEmailOptIn());
    }
}
