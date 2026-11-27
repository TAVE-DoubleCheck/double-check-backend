package kr.tave.minicore.simulator;

/**
 * 해약환급금 값의 신뢰 수준 (명세 4장). 예시표는 상품별 대표 조건 1개뿐이라
 * 입력 조건과 얼마나 맞는지를 응답에 그대로 드러낸다.
 */
public enum ExampleBasis {

    /** 입력 조건이 예시표 조건과 완전히 같음 → 금액·환급률 모두 예시표 값 */
    EXAMPLE_EXACT,

    /** 납입기간은 같지만 나이·성별·금액이 다름 → 환급률만 참고용으로 제공 (가입금액 비례 환산 안 함) */
    REFERENCE_RATE,

    /** 납입기간이 예시표와 다름 → 제공할 값 없음 */
    NOT_AVAILABLE
}
