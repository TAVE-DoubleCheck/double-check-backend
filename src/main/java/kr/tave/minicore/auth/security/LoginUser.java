package kr.tave.minicore.auth.security;

import kr.tave.minicore.auth.domain.Role;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * 로그인한 사람. JWT를 검증하면 이 값이 Spring Security 인증 정보에 들어간다.
 *
 * 컨트롤러에서 꺼내는 법:
 * <pre>
 * &#64;GetMapping("/api/contracts")
 * public List&lt;...&gt; myContracts(&#64;AuthenticationPrincipal LoginUser me) {
 *     return contractQueryService.findByUser(me.id());
 * }
 * </pre>
 * 토큰 안의 값만 쓰고 DB를 다시 읽지 않는다. 이름·생년월일이 필요하면 UserRepository로 id 조회.
 */
public record LoginUser(long id, String email, Role role) implements AuthenticatedPrincipal {

    /** Principal.getName() 값 = 회원 id 문자열 (로그 등에 이메일을 남기지 않도록) */
    @Override
    public String getName() {
        return String.valueOf(id);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
