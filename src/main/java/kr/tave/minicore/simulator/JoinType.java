package kr.tave.minicore.simulator;

/**
 * 가입 종류 → 상품 코드 매핑 (명세 7장: 매핑은 한 곳에).
 * 코드는 보험료 API 상품코드(product.product_code)다. 약관코드 P4000xx 아님 — seed/product_seed.sql 참고.
 */
public enum JoinType {

    /** 1종(일반가입): 표준형 4008400(P400084) / 50%지급형 4008300(P400083) */
    GENERAL("4008400", "4008300"),

    /** 2종(간편가입): 표준형 4008600(P400086) / 50%지급형 4008500(P400085) */
    SIMPLIFIED("4008600", "4008500");

    private final String standardCode;
    private final String halfSurrenderCode;

    JoinType(String standardCode, String halfSurrenderCode) {
        this.standardCode = standardCode;
        this.halfSurrenderCode = halfSurrenderCode;
    }

    public String standardCode() {
        return standardCode;
    }

    public String halfSurrenderCode() {
        return halfSurrenderCode;
    }
}
