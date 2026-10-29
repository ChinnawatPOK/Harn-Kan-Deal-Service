package com.harnkan.deal.service;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CreateDealCommand {
    String creatorId;
    String creatorName;
    String dealName;
    String details;
    String category;
    String menuImageUrl;
    String pickupLocation;
    BigDecimal latitude;
    BigDecimal longitude;
    BigDecimal totalPrice;
    Integer targetSlots;
    Instant expiresAt;
}
