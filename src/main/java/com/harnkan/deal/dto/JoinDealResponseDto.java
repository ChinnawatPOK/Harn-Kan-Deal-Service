package com.harnkan.deal.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record JoinDealResponseDto(
        String paymentId,
        BigDecimal amountToPay,
        Instant reservedUntil,
        DealDetailDto deal) {
}
