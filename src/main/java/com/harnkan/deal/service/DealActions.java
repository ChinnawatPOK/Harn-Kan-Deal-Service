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
                isHost && open && !anyReceived(rows),
                me != null && me.getRole() == ParticipantRole.PARTICIPANT
                        && status == ParticipantStatus.HELD && fullyPaid(deal, rows));
    }

    /** Not holding a slot already, deal open, not expired, a slot is free. */
    public static boolean canJoin(Deal deal, DealParticipant me, Instant now) {
        boolean holdsSlot = me != null && me.getStatus().isCounted();
        return !holdsSlot
                && deal.getStatus() == DealStatus.ACTIVE
                && deal.getExpiresAt().isAfter(now)
                && deal.getJoinedSlots() < deal.getTargetSlots();
    }

    /**
     * "Fully paid" (api-design.md section 2): every slot is taken (LOCKED) and every participant has paid
     * (HELD) or already confirmed receipt (RECEIVED). From here participants may confirm receipt and the
     * deal no longer expires. There is no host "mark purchased" step.
     */
    public static boolean fullyPaid(Deal deal, Collection<DealParticipant> rows) {
        return deal.getStatus() == DealStatus.LOCKED && rows.stream()
                .filter(p -> p.getRole() == ParticipantRole.PARTICIPANT && p.getStatus().isCounted())
                .allMatch(p -> p.getStatus() == ParticipantStatus.HELD || p.getStatus() == ParticipantStatus.RECEIVED);
    }

    /** Every participant still in the deal confirmed receipt (and there is at least one): time to pay the host. */
    public static boolean allReceived(Collection<DealParticipant> rows) {
        List<DealParticipant> participants = rows.stream()
                .filter(p -> p.getRole() == ParticipantRole.PARTICIPANT && p.getStatus().isCounted())
                .toList();
        return !participants.isEmpty()
                && participants.stream().allMatch(p -> p.getStatus() == ParticipantStatus.RECEIVED);
    }

    /** Someone already confirmed receipt: from then on the host can no longer cancel. */
    public static boolean anyReceived(Collection<DealParticipant> rows) {
        return rows.stream().anyMatch(p -> p.getStatus() == ParticipantStatus.RECEIVED);
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
