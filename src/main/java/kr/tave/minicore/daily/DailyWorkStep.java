package kr.tave.minicore.daily;

import java.time.LocalDate;

/**
 * 담당 업무를 구현한 Spring 빈의 연결 지점.
 * execute는 호출자의 트랜잭션에 참여해야 하며, 실패를 삼키거나 별도로 커밋하면 안 된다.
 */
public interface DailyWorkStep {
    DailyWorkStage stage();

    void execute(LocalDate businessDate);
}
