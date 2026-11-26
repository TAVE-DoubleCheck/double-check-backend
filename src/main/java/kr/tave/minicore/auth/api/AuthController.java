package kr.tave.minicore.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.auth.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "로그인", description = "회원가입·로그인·로그아웃·내 정보")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "회원가입 (고객)", description = "생년월일·성별은 보험료 계산에 쓰이며 가입 후 바꿀 수 없습니다.")
    @SecurityRequirements
    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(req));
    }

    @Operation(summary = "로그인", description = "성공하면 accessToken을 줍니다. 이후 요청에 Authorization: Bearer {accessToken}")
    @SecurityRequirements
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @Operation(summary = "로그아웃", description = "서버에 저장한 로그인 정보가 없어 프론트가 토큰을 지우면 끝납니다. 화면 흐름을 맞추기 위한 주소입니다.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 정보")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal LoginUser me) {
        return authService.me(me);
    }
}
