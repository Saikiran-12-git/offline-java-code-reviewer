package dev.saikiran.reviewer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(@NotBlank @Size(max = 6000) String code, @NotNull Focus focus) {
    public enum Focus { BUGS, SECURITY, PERFORMANCE, READABILITY }
}
