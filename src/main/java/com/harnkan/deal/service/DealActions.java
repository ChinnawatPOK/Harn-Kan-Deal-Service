package com.harnkan.deal.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealParticipant;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.domain.ParticipantRole;
import com.harnkan.deal.domain.ParticipantStatus;
import com.harnkan.deal.dto.ActionsDto;

/** The one place that decides which buttons a caller may press (api-design.md DR4 me.actions). */
public final class DealActions {

    public static final Set<DealStatus> OPEN = EnumSet.of(DealStatus.ACTIVE, DealStatus.LOCKED);

    private DealActions() {
    }

    /**
     * @param me   the caller's row in this deal, or null when they never joined
     * @param rows every row of the deal (used to see whether all participants have paid)
     */
    public static ActionsDto forCaller(Deal deal, DealParticipant me, List<DealParticipant> rows, Instant now) {
        ParticipantStatus status = me == null ? null : me.getStatus();
        boolean isHost = me != null && me.getRole() == ParticipantRole.HOST;
        boolean open = OPEN.contains(deal.getStatus());

        return new ActionsDto(
                canJoin(deal, me, now),
                status == ParticipantStatus.RESERVED && open,
                (status == ParticipantStatus.RESERVED || status == ParticipantStatus.PAYMENT_PENDING) && open,
                isHost && open,
                isHost && deal.getStatus() == DealStatus.LOCKED && allParticipantsHeld(rows),
                me != null && me.getRole() == ParticipantRole.PARTICIPANT
                        && status == ParticipantStatus.HELD && deal.getStatus() == DealStatus.PURCHASED);
    }

    /** Not holding a slot already, deal open, not expired, a slot is free. */
    public static boolean canJoin(Deal deal, DealParticipant me, Instant now) {
        boolean holdsSlot = me != null && me.getStatus().isCounted();
        return !holdsSlot
                && deal.getStatus() == DealStatus.ACTIVE
                && deal.getExpiresAt().isAfter(now)
                && deal.getJoinedSlots() < deal.getTargetSlots();
    }

    /** Every participant that still holds a slot has paid (HELD). */
    public static boolean allParticipantsHeld(Collection<DealParticipant> rows) {
        return rows.stream()
                .filter(p -> p.getRole() == ParticipantRole.PARTICIPANT && p.getStatus().isCounted())
                .allMatch(p -> p.getStatus() == ParticipantStatus.HELD);
    }

    /**
     * Picks the row that represents a user in a deal: the live one if there is one,
     * otherwise the most recent (e.g. a FAILED row after leaving).
     */
    public static DealParticipant currentRow(Collection<DealParticipant> userRows) {
        return userRows.stream()
                .max(Comparator.comparing((DealParticipant p) -> p.getStatus() != ParticipantStatus.FAILED)
                        .thenComparing(DealParticipant::getJoinedAt))
                .orElse(null);
    }
}
