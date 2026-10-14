package com.harnkan.deal.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CreateDealCommand {
    String creatorId;
    String shopName;
    String promotionDetail;
    String category;
    String menuImageUrl;
    BigDecimal pricePerSlot;
    Integer targetSlots;
    BigDecimal latitude;
    BigDecimal longitude;
    LocalDateTime expiresAt;
}
