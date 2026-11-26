package kr.tave.minicore.auth.domain;

/** 회원 역할 (V4 users.role). 고객은 회원가입으로, 직원은 기동 시 자동 생성으로만 만든다. */
public enum Role {
    CUSTOMER,
    ADMIN;

    /** Spring Security 권한 이름. hasRole("ADMIN")이 "ROLE_ADMIN"을 찾는다 */
    public String authority() {
        return "ROLE_" + name();
    }
}
