package kr.tave.minicore.daily;

import java.time.LocalDate;

/** 앞서 완료한 날짜는 유지되고 failedDate의 변경은 롤백된다. */
public class DailyWorkException extends RuntimeException {
    private final LocalDate failedDate;

    public DailyWorkException(LocalDate failedDate, Throwable cause) {
        super("Daily work failed on " + failedDate, cause);
        this.failedDate = failedDate;
    }

    public LocalDate failedDate() {
        return failedDate;
    }
}
