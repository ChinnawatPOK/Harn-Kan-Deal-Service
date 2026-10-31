package com.harnkan.deal.service;

import static com.harnkan.deal.TestData.NOW;
import static com.harnkan.deal.TestData.deal;
import static com.harnkan.deal.TestData.host;
import static com.harnkan.deal.TestData.participant;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealParticipant;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.domain.ParticipantStatus;
import com.harnkan.deal.dto.ActionsDto;

class DealActionsTest {

    private static ActionsDto actions(Deal d, DealParticipant me, DealParticipant... rows) {
        return DealActions.forCaller(d, me, List.of(rows), NOW);
    }

    @Test
    void outsider_canJoinOpenDealWithFreeSlot() {
        Deal d = deal("d", DealStatus.ACTIVE, 2, 4);

        assertThat(DealActions.canJoin(d, null, NOW)).isTrue();
        assertThat(actions(d, null, host("d")).canJoin()).isTrue();
    }

    @Test
    void outsider_cannotJoinFullExpiredOrLockedDeal() {
        assertThat(DealActions.canJoin(deal("d", DealStatus.ACTIVE, 4, 4), null, NOW)).isFalse();
        assertThat(DealActions.canJoin(deal("d", DealStatus.LOCKED, 4, 4), null, NOW)).isFalse();
        Deal expired = deal("d", DealStatus.ACTIVE, 1, 4);
        expired.setExpiresAt(NOW);
        assertThat(DealActions.canJoin(expired, null, NOW)).isFalse();
    }

    @Test
    void reservedParticipant_canPayAndLeave_notJoin() {
        Deal d = deal("d", DealStatus.ACTIVE, 2, 4);
        DealParticipant me = participant("p", "d", "anya", ParticipantStatus.RESERVED);

        ActionsDto a = actions(d, me, host("d"), me);

        assertThat(a.canPay()).isTrue();
        assertThat(a.canLeave()).isTrue();
        assertThat(a.canJoin()).isFalse();
        assertThat(a.canCancel()).isFalse();
    }

    @Test
    void pendingParticipant_canLeaveButNotPay() {
        Deal d = deal("d", DealStatus.ACTIVE, 2, 4);
        DealParticipant me = participant("p", "d", "anya", ParticipantStatus.PAYMENT_PENDING);

        ActionsDto a = actions(d, me, host("d"), me);

        assertThat(a.canPay()).isFalse();
        assertThat(a.canLeave()).isTrue();
    }

    @Test
    void paidParticipant_cannotLeave_andConfirmsReceiptOnlyWhenEveryoneHasPaid() {
        DealParticipant me = participant("p", "d", "anya", ParticipantStatus.HELD);
        DealParticipant stillPaying = participant("p2", "d", "narin", ParticipantStatus.RESERVED);
        DealParticipant received = participant("p3", "d", "mali", ParticipantStatus.RECEIVED);

        ActionsDto notFull = actions(deal("d", DealStatus.ACTIVE, 2, 3), me, host("d"), me);
        ActionsDto someoneUnpaid = actions(deal("d", DealStatus.LOCKED, 3, 3), me, host("d"), me, stillPaying);
        ActionsDto fullyPaid = actions(deal("d", DealStatus.LOCKED, 3, 3), me, host("d"), me, received);

        assertThat(fullyPaid.canLeave()).isFalse();
        assertThat(notFull.canConfirmReceipt()).isFalse();
        assertThat(someoneUnpaid.canConfirmReceipt()).isFalse();
        assertThat(fullyPaid.canConfirmReceipt()).isTrue(); // no host "mark purchased" step any more
    }

    @Test
    void leftParticipant_canJoinAgain() {
        Deal d = deal("d", DealStatus.ACTIVE, 1, 4);
        DealParticipant me = participant("p", "d", "anya", ParticipantStatus.FAILED);

        assertThat(actions(d, me, host("d"), me).canJoin()).isTrue();
    }

    @Test
    void host_canCancelWhileOpen_untilSomeoneConfirmsReceipt() {
        DealParticipant host = host("d");
        DealParticipant paid = participant("p2", "d", "narin", ParticipantStatus.HELD);
        DealParticipant received = participant("p3", "d", "anya", ParticipantStatus.RECEIVED);

        assertThat(actions(deal("d", DealStatus.ACTIVE, 1, 4), host, host).canCancel()).isTrue();
        assertThat(actions(deal("d", DealStatus.LOCKED, 3, 3), host, host, paid, paid).canCancel()).isTrue();
        assertThat(actions(deal("d", DealStatus.LOCKED, 3, 3), host, host, paid, received).canCancel()).isFalse();
        assertThat(actions(deal("d", DealStatus.COMPLETED, 3, 3), host, host).canCancel()).isFalse();
        assertThat(actions(deal("d", DealStatus.ACTIVE, 1, 4), host, host).canJoin()).isFalse();
    }

    @Test
    void fullyPaid_needsLockedAndEveryParticipantHeldOrReceived() {
        DealParticipant host = host("d");
        DealParticipant paid = participant("p2", "d", "narin", ParticipantStatus.HELD);
        DealParticipant received = participant("p3", "d", "anya", ParticipantStatus.RECEIVED);
        DealParticipant waiting = participant("p4", "d", "mali", ParticipantStatus.RESERVED);
        DealParticipant left = participant("p5", "d", "chai", ParticipantStatus.FAILED);

        assertThat(DealActions.fullyPaid(deal("d", DealStatus.LOCKED, 3, 3), List.of(host, paid, received, left))).isTrue();
        assertThat(DealActions.fullyPaid(deal("d", DealStatus.LOCKED, 3, 3), List.of(host, paid, waiting))).isFalse();
        assertThat(DealActions.fullyPaid(deal("d", DealStatus.ACTIVE, 2, 3), List.of(host, paid))).isFalse();
    }

    @Test
    void currentRow_prefersLiveRowOverFailedOne() {
        DealParticipant failed = participant("old", "d", "anya", ParticipantStatus.FAILED);
        DealParticipant live = participant("new", "d", "anya", ParticipantStatus.RESERVED);
        failed.setJoinedAt(NOW);
        live.setJoinedAt(NOW.minusSeconds(100));

        assertThat(DealActions.currentRow(List.of(failed, live))).isSameAs(live);
        assertThat(DealActions.currentRow(List.of(failed))).isSameAs(failed);
        assertThat(DealActions.currentRow(List.of())).isNull();
    }
}
