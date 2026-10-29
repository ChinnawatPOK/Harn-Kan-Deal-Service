package com.harnkan.deal.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record MeDto(
        String role,
        String status,
        String paymentId,
        BigDecimal amountToPay,
        BigDecimal refundAmount,
        Instant reservedUntil,
        ActionsDto actions) {
}
