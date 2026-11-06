package kr.tave.minicore.clock;

import java.time.LocalDate;

/** 업무 날짜. 유효기간 판단·보험나이 계산 등 업무 날짜는 전부 이 인터페이스만 쓴다 (LocalDate.now() 금지). */
public interface ClockService {

    /** 업무 날짜 = system_clock.business_date */
    LocalDate today();
}
