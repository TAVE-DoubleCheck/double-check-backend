package kr.tave.minicore.contract.api;

import jakarta.validation.constraints.NotNull;

public record AutoDebitRequest(@NotNull Boolean enabled) {
}
