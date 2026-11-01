package com.harnkan.deal.messaging;

import java.util.List;

/**
 * deal.cancelled / deal.expired: the fields of deal.created plus {@code participantIds}, the users to tell
 * (participants whose money is at Payment and is being refunded; the host is never included).
 */
public record DealClosedEvent(
        String eventId,
        String eventType,
        String occurredAt,
        String dealId,
        String creatorId,
        String category,
        String shopName,
        Double lat,
        Double lng,
        List<String> participantIds) {
}
