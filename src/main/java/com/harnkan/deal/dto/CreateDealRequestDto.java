package com.harnkan.deal.dto;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.service.CreateDealCommand;
import com.harnkan.deal.web.CurrentUser;

public record CreateDealRequestDto(
        String dealName,
        String category,
        String pickupLocation,
        Double latitude,
        Double longitude,
        String details,
        BigDecimal totalPrice,
        Integer targetSlots,
        Integer expiresInMinutes,
        String menuImageUrl) {

    public static final int MIN_EXPIRES_MINUTES = 5;
    public static final int MAX_EXPIRES_MINUTES = 1440;

    public CreateDealCommand toCommand(CurrentUser user, Clock clock) {
        if (expiresInMinutes == null || expiresInMinutes < MIN_EXPIRES_MINUTES || expiresInMinutes > MAX_EXPIRES_MINUTES) {
            throw new DealValidationException(
                    "expiresInMinutes must be between " + MIN_EXPIRES_MINUTES + " and " + MAX_EXPIRES_MINUTES);
        }
        if (latitude == null || longitude == null) {
            throw new DealValidationException("latitude and longitude are required");
        }
        Instant expiresAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(expiresInMinutes));
        return CreateDealCommand.builder()
                .creatorId(user.id())
                .creatorName(user.name())
                .dealName(dealName)
                .details(details)
                .category(category)
                .menuImageUrl(menuImageUrl)
                .pickupLocation(pickupLocation)
                .latitude(BigDecimal.valueOf(latitude))
                .longitude(BigDecimal.valueOf(longitude))
                .totalPrice(totalPrice)
                .targetSlots(targetSlots)
                .expiresAt(expiresAt)
                .build();
    }
}
