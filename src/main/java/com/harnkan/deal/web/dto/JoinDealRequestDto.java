package com.harnkan.deal.web.dto;

/**
 * Request body for DR7 {@code POST /api/deals/{dealId}/join}.
 *
 * In the full architecture the participant id comes from the {@code X-User-Id} header the
 * Gateway attaches after validating the caller's JWT (see project doc section 6). There is no
 * Gateway/JWT filter wired up yet, so for now the participant id is accepted directly in the
 * body to keep the demo runnable end-to-end.
 */
public record JoinDealRequestDto(String participantId) {
}
