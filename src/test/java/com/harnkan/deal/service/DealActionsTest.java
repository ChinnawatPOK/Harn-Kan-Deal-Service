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
    void paidParticipant_cannotLeave_andConfirmsReceiptOnlyAfterPurchase() {
        DealParticipant me = participant("p", "d", "anya", ParticipantStatus.HELD);

        ActionsDto locked = actions(deal("d", DealStatus.LOCKED, 2, 2), me, host("d"), me);
        ActionsDto purchased = actions(deal("d", DealStatus.PURCHASED, 2, 2), me, host("d"), me);

        assertThat(locked.canLeave()).isFalse();
        assertThat(locked.canConfirmReceipt()).isFalse();
        assertThat(purchased.canConfirmReceipt()).isTrue();
    }

    @Test
    void leftParticipant_canJoinAgain() {
        Deal d = deal("d", DealStatus.ACTIVE, 1, 4);
        DealParticipant me = participant("p", "d", "anya", ParticipantStatus.FAILED);

        assertThat(actions(d, me, host("d"), me).canJoin()).isTrue();
    }

    @Test
    void host_canCancelWhileOpen_butNotAfterPurchase() {
        DealParticipant host = host("d");

        assertThat(actions(deal("d", DealStatus.ACTIVE, 1, 4), host, host).canCancel()).isTrue();
        assertThat(actions(deal("d", DealStatus.LOCKED, 4, 4), host, host).canCancel()).isTrue();
        assertThat(actions(deal("d", DealStatus.PURCHASED, 4, 4), host, host).canCancel()).isFalse();
        assertThat(actions(deal("d", DealStatus.ACTIVE, 1, 4), host, host).canJoin()).isFalse();
    }

    @Test
    void host_canMarkPurchasedOnlyWhenLockedAndEveryoneHasPaid() {
        DealParticipant host = host("d");
        DealParticipant paid = participant("p2", "d", "narin", ParticipantStatus.HELD);
        DealParticipant waiting = participant("p3", "d", "anya", ParticipantStatus.RESERVED);

        assertThat(actions(deal("d", DealStatus.LOCKED, 3, 3), host, host, paid, waiting).canMarkPurchased()).isFalse();
        assertThat(actions(deal("d", DealStatus.LOCKED, 2, 2), host, host, paid).canMarkPurchased()).isTrue();
        assertThat(actions(deal("d", DealStatus.ACTIVE, 2, 3), host, host, paid).canMarkPurchased()).isFalse();
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
