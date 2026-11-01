package com.harnkan.deal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record NearbyItemDto(
        String id,
        String dealName,
        String details,
        String category,
        BigDecimal pricePerSlot,
        int joinedSlots,
        int targetSlots,
        Instant expiresAt,
        int distanceMeters,
        List<String> participantInitials) {
}
