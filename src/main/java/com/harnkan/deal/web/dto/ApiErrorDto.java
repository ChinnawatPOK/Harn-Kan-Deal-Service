package com.harnkan.deal.web.dto;

import java.time.Instant;

/** Common error shape for every REST endpoint (project doc section 6: "{code, message, timestamp}", ISO-8601 UTC). */
public record ApiErrorDto(String code, String message, Instant timestamp) {

    public static ApiErrorDto of(String code, String message) {
        return new ApiErrorDto(code, message, Instant.now());
    }
}
