package com.harnkan.deal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** DR11: the caller's receipt of a COMPLETED deal. */
public record ReceiptDto(
        String receiptNo,
        String dealId,
        String dealName,
        String category,
        Instant completedAt,
        BigDecimal totalPrice,
        BigDecimal pricePerSlot,
        String role,
        BigDecimal amount,
        String paymentId,
        BigDecimal payoutToHost,
        List<ReceiptLineDto> lines) {
}
