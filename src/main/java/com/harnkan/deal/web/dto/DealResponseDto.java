package com.harnkan.deal.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.harnkan.deal.domain.Deal;

/** Response body for DR2/DR4/DR6 — mirrors the {@code deals} table 1:1. */
public record DealResponseDto(
        String id,
        String creatorId,
        String shopName,
        String promotionDetail,
        String category,
        String menuImageUrl,
        BigDecimal pricePerSlot,
        Integer targetSlots,
        Integer joinedSlots,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDateTime expiresAt,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static DealResponseDto from(Deal deal) {
        return new DealResponseDto(
                deal.getId(),
                deal.getCreatorId(),
                deal.getShopName(),
                deal.getPromotionDetail(),
                deal.getCategory(),
                deal.getMenuImageUrl(),
                deal.getPricePerSlot(),
                deal.getTargetSlots(),
                deal.getJoinedSlots(),
                deal.getLatitude(),
                deal.getLongitude(),
                deal.getExpiresAt(),
                deal.getStatus().name(),
                deal.getCreatedAt(),
                deal.getUpdatedAt());
    }
}
