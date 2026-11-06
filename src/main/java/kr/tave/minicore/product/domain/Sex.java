package kr.tave.minicore.product.domain;

/** 성별. DB premium_rate.sex의 CHAR(1) 값('M'/'W')과 이름이 같다. */
public enum Sex {
    M, W;

    /** DB 컬럼(CHAR(1))에 쓰는 문자 */
    public char code() {
        return name().charAt(0);
    }
}
