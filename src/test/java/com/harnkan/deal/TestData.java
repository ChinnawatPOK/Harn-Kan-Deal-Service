package com.harnkan.deal;

import java.math.BigDecimal;
import java.time.Instant;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealParticipant;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.domain.ParticipantRole;
import com.harnkan.deal.domain.ParticipantStatus;

/** Builders shared by the tests. */
public final class TestData {

    public static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

    private TestData() {
    }

    public static Deal deal(String id, DealStatus status, int joined, int target) {
        return Deal.builder()
                .id(id)
                .creatorId("host-1")
                .creatorName("Ploy S.")
                .dealName("Katsu & Co. group order")
                .details("Chicken katsu set")
                .category("FOOD")
                .pickupLocation("Ari BTS exit 3")
                .latitude(new BigDecimal("13.77980000"))
                .longitude(new BigDecimal("100.54460000"))
                .totalPrice(new BigDecimal("756.00"))
                .pricePerSlot(new BigDecimal("189.00"))
                .targetSlots(target)
                .joinedSlots(joined)
                .expiresAt(NOW.plusSeconds(1800))
                .status(status)
                .createdAt(NOW.minusSeconds(60))
                .updatedAt(NOW.minusSeconds(60))
                .build();
    }

    public static DealParticipant host(String dealId) {
        return DealParticipant.builder().id("p-host").dealId(dealId).userId("host-1").userName("Ploy S.")
                .role(ParticipantRole.HOST).status(ParticipantStatus.HOST).joinedAt(NOW.minusSeconds(60))
                .updatedAt(NOW.minusSeconds(60)).build();
    }

    public static DealParticipant participant(String rowId, String dealId, String userId, ParticipantStatus status) {
        return DealParticipant.builder().id(rowId).dealId(dealId).userId(userId).userName("User " + userId)
                .role(ParticipantRole.PARTICIPANT).paymentId("pay-" + userId).status(status)
                .reservedUntil(status == ParticipantStatus.RESERVED ? NOW.plusSeconds(300) : null)
                .joinedAt(NOW.minusSeconds(30)).updatedAt(NOW.minusSeconds(30)).build();
    }
}
