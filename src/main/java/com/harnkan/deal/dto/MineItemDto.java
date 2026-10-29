package com.harnkan.deal.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record MineItemDto(
        String id,
        String dealName,
        String category,
        String dealStatus,
        String myRole,
        String myStatus,
        BigDecimal totalPrice,
        BigDecimal pricePerSlot,
        int joinedSlots,
        int targetSlots,
        int readySlots,
        boolean readyToOrder,
        Instant expiresAt,
        Instant createdAt) {
}
