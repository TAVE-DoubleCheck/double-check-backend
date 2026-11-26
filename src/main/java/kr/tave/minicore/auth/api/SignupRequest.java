package kr.tave.minicore.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.tave.minicore.product.domain.Sex;

import java.time.LocalDate;

/**
 * 회원가입 요청. 생년월일·성별은 가입 후 바꿀 수 없다 (보험료 계산 원천).
 * 비밀번호 최대 64자: BCrypt는 72바이트까지만 쓰므로 한글을 섞어도 넘지 않도록 여유를 둔다.
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Size(max = 100) String name,
        @NotNull LocalDate birthDate,
        @NotNull Sex sex
) {
}
