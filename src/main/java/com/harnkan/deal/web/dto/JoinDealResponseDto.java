package com.harnkan.deal.web.dto;

import java.time.LocalDateTime;

import com.harnkan.deal.service.JoinDealResult;

/** Response body for DR7 {@code POST /api/deals/{dealId}/join}. */
public record JoinDealResponseDto(String paymentId, LocalDateTime reservedUntil, DealResponseDto deal) {

    public static JoinDealResponseDto from(JoinDealResult result) {
        return new JoinDealResponseDto(
                result.getPaymentId(), result.getReservedUntil(), DealResponseDto.from(result.getDeal()));
    }
}
