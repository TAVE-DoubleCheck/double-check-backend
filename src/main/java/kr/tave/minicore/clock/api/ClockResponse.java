package kr.tave.minicore.clock.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

public record ClockResponse(
        @Schema(description = "system_clock.business_date 기준 업무 날짜", example = "2026-11-12")
        LocalDate businessDate) {
}
