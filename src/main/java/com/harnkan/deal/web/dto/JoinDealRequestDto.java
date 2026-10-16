package com.harnkan.deal.web.dto;

/**
 * Request body for DR7 {@code POST /api/deals/{dealId}/join}.
 *
 * The REST layer validates the caller's JWT. The participant id is still accepted from the
 * request body; ownership checks against the authenticated subject are not implemented here.
 */
public record JoinDealRequestDto(String participantId) {
}
