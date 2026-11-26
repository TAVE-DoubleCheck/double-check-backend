package kr.tave.minicore.contract.domain;

/**
 * 계약 상태를 바꾼 주체 (V4 contract_history.actor_type / actor_id).
 * 고객·직원은 로그인 계정 id를 남기고, 일 배치는 사람이 아니라 id가 없다.
 */
public record Actor(Type type, Long userId) {

    public enum Type { CUSTOMER, ADMIN, BATCH }

    public Actor {
        if (type == null) {
            throw new IllegalArgumentException("actor type은 필수");
        }
        if (type == Type.BATCH && userId != null) {
            throw new IllegalArgumentException("배치는 userId가 없다");
        }
        if (type != Type.BATCH && userId == null) {
            throw new IllegalArgumentException(type + "는 userId가 필요하다");
        }
    }

    public static Actor customer(long userId) {
        return new Actor(Type.CUSTOMER, userId);
    }

    public static Actor admin(long userId) {
        return new Actor(Type.ADMIN, userId);
    }

    public static Actor batch() {
        return new Actor(Type.BATCH, null);
    }
}
