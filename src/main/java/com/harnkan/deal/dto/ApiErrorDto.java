package com.harnkan.deal.dto;

import java.time.Instant;

public record ApiErrorDto(String code, String message, Instant timestamp) {

    public static ApiErrorDto of(String code, String message) {
        return new ApiErrorDto(code, message, Instant.now());
    }
}
