package com.harnkan.deal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DealDetailDto(
        String id,
        String dealName,
        String details,
        String category,
        String menuImageUrl,
        String pickupLocation,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal totalPrice,
        BigDecimal pricePerSlot,
        int targetSlots,
        int joinedSlots,
        String status,
        Instant expiresAt,
        Instant purchasedAt,
        Instant completedAt,
        Instant cancelledAt,
        Instant createdAt,
        Integer distanceMeters,
        List<ParticipantDto> participants,
        MeDto me,
        JoinOnlyActionsDto actionsIfNotJoined) {
}
