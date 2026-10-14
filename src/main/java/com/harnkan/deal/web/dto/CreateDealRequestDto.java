package com.harnkan.deal.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Request body for DR2 {@code POST /api/deals}. */
public record CreateDealRequestDto(
        String creatorId,
        String shopName,
        String promotionDetail,
        String category,
        String menuImageUrl,
        BigDecimal pricePerSlot,
        Integer targetSlots,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDateTime expiresAt) {
}
