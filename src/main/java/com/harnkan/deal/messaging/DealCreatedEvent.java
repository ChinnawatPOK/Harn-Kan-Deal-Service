package com.harnkan.deal.messaging;

public record DealCreatedEvent(
        String eventId,
        String eventType,
        String occurredAt,
        String dealId,
        String creatorId,
        String category,
        String shopName,
        Double lat,
        Double lng) {
}
