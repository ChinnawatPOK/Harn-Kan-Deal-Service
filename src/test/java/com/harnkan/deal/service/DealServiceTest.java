package com.harnkan.deal.service;

import static com.harnkan.deal.TestData.NOW;
import static com.harnkan.deal.TestData.deal;
import static com.harnkan.deal.TestData.host;
import static com.harnkan.deal.TestData.participant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpIOException;

import com.harnkan.deal.client.PaymentServiceClient;
import com.harnkan.deal.client.PaymentTimeParser;
import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealParticipant;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.domain.ParticipantRole;
import com.harnkan.deal.domain.ParticipantStatus;
import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.exception.ParticipantNotFoundException;
import com.harnkan.deal.exception.PaymentServiceException;
import com.harnkan.deal.grpc.payment.CancelPaymentResponse;
import com.harnkan.deal.grpc.payment.CreatePaymentResponse;
import com.harnkan.deal.grpc.payment.PaymentStatusItem;
import com.harnkan.deal.grpc.payment.RefundDealResponse;
import com.harnkan.deal.messaging.DealEventPublisher;
import com.harnkan.deal.repository.DealParticipantRepository;
import com.harnkan.deal.repository.DealRepository;
import com.harnkan.deal.web.CurrentUser;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DealServiceTest {

    private static final String DEAL_ID = "deal-1";
    private static final CurrentUser ANYA = new CurrentUser("anya", "Anya P.");

    @Mock
    private DealRepository dealRepository;
    @Mock
    private DealParticipantRepository participantRepository;
    @Mock
    private PaymentServiceClient paymentClient;
    @Mock
    private DealEventPublisher publisher;

    private DealService service;

    @BeforeEach
    void setUp() {
        service = new DealService(dealRepository, participantRepository, paymentClient,
                new PaymentTimeParser("UTC"), publisher, Clock.fixed(NOW, ZoneOffset.UTC));
        when(dealRepository.save(any(Deal.class))).thenAnswer(i -> i.getArgument(0));
        when(participantRepository.save(any(DealParticipant.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Deal givenDeal(DealStatus status, int joined, int target, DealParticipant... rows) {
        Deal d = deal(DEAL_ID, status, joined, target);
        when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(d));
        when(participantRepository.findByDealIdOrderByJoinedAtAsc(DEAL_ID)).thenReturn(List.of(rows));
        return d;
    }

    private static CreateDealCommand.CreateDealCommandBuilder validCommand() {
        return CreateDealCommand.builder().creatorId("host-1").creatorName("Ploy S.").dealName("Pizza")
                .category("food").pickupLocation("Ari").latitude(new BigDecimal("13.7")).longitude(new BigDecimal("100.5"))
                .totalPrice(new BigDecimal("760")).targetSlots(3).expiresAt(NOW.plusSeconds(1800));
    }

    // --- create ---------------------------------------------------------------------

    @Test
    void createDeal_roundsPricePerSlotUpToWholeBahtAndAddsHostRow() {
        Deal deal = service.createDeal(validCommand().build());

        assertThat(deal.getPricePerSlot()).isEqualByComparingTo("254.00"); // 760 / 3 = 253.33 -> 254
        assertThat(deal.getPricePerSlot().scale()).isEqualTo(2);
        assertThat(deal.getJoinedSlots()).isEqualTo(1);
        assertThat(deal.getCategory()).isEqualTo("FOOD");
        assertThat(deal.getStatus()).isEqualTo(DealStatus.ACTIVE);

        ArgumentCaptor<DealParticipant> row = ArgumentCaptor.forClass(DealParticipant.class);
        verify(participantRepository).save(row.capture());
        assertThat(row.getValue().getRole()).isEqualTo(ParticipantRole.HOST);
        assertThat(row.getValue().getStatus()).isEqualTo(ParticipantStatus.HOST);
        assertThat(row.getValue().getUserId()).isEqualTo("host-1");
        verify(publisher).publishDealCreated(deal);
    }

    @Test
    void pricePerSlot_exactDivisionStaysExact() {
        assertThat(DealService.pricePerSlot(new BigDecimal("756.00"), 4)).isEqualByComparingTo("189.00");
    }

    @Test
    void createDeal_survivesBrokerOutage() {
        org.mockito.Mockito.doThrow(new AmqpIOException(new java.io.IOException("down")))
                .when(publisher).publishDealCreated(any());

        assertThat(service.createDeal(validCommand().build()).getId()).isNull(); // id is assigned by JPA on persist
    }

    @Test
    void createDeal_rejectsBadInput() {
        assertThatThrownBy(() -> service.createDeal(validCommand().targetSlots(1).build()))
                .isInstanceOf(DealValidationException.class);
        assertThatThrownBy(() -> service.createDeal(validCommand().targetSlots(21).build()))
                .isInstanceOf(DealValidationException.class);
        assertThatThrownBy(() -> service.createDeal(validCommand().totalPrice(BigDecimal.ZERO).build()))
                .isInstanceOf(DealValidationException.class);
        assertThatThrownBy(() -> service.createDeal(validCommand().category("TOYS").build()))
                .isInstanceOf(DealValidationException.class);
        assertThatThrownBy(() -> service.createDeal(validCommand().expiresAt(NOW.minusSeconds(1)).build()))
                .isInstanceOf(DealValidationException.class);
        assertThatThrownBy(() -> service.createDeal(validCommand().latitude(new BigDecimal("91")).build()))
                .isInstanceOf(DealValidationException.class);
        assertThatThrownBy(() -> service.createDeal(validCommand().pickupLocation(" ").build()))
                .isInstanceOf(DealValidationException.class);
    }

    // --- join -----------------------------------------------------------------------

    private void paymentWillReserve() {
        when(paymentClient.createPayment(DEAL_ID, "anya", new BigDecimal("189.00"))).thenReturn(
                CreatePaymentResponse.newBuilder().setPaymentId("pay-anya").setStatus("RESERVED")
                        .setReservedUntil("2026-10-24T08:05:00").build());
    }

    @Test
    void joinDeal_reservesSlotAndStoresPayment() {
        Deal d = givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));
        paymentWillReserve();

        JoinDealResult result = service.joinDeal(DEAL_ID, ANYA);

        assertThat(d.getJoinedSlots()).isEqualTo(2);
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
        DealParticipant p = result.getParticipant();
        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.RESERVED);
        assertThat(p.getPaymentId()).isEqualTo("pay-anya");
        assertThat(p.getUserName()).isEqualTo("Anya P.");
        assertThat(p.getReservedUntil()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void joinDeal_lastSlotLocksTheDeal() {
        Deal d = givenDeal(DealStatus.ACTIVE, 3, 4, host(DEAL_ID));
        paymentWillReserve();

        service.joinDeal(DEAL_ID, ANYA);

        assertThat(d.getJoinedSlots()).isEqualTo(4);
        assertThat(d.getStatus()).isEqualTo(DealStatus.LOCKED);
    }

    @Test
    void joinDeal_hostCannotJoin() {
        givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));

        assertThatThrownBy(() -> service.joinDeal(DEAL_ID, new CurrentUser("host-1", "Ploy S.")))
                .isInstanceOf(DealStateException.class);
        verify(paymentClient, never()).createPayment(anyString(), anyString(), any());
    }

    @Test
    void joinDeal_twiceIsAlreadyJoined() {
        givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID), participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED));

        assertThatThrownBy(() -> service.joinDeal(DEAL_ID, ANYA))
                .isInstanceOfSatisfying(DealStateException.class, e -> assertThat(e.getCode()).isEqualTo("ALREADY_JOINED"));
    }

    @Test
    void joinDeal_fullDealIsRejected() {
        givenDeal(DealStatus.LOCKED, 4, 4, host(DEAL_ID));

        assertThatThrownBy(() -> service.joinDeal(DEAL_ID, ANYA))
                .isInstanceOfSatisfying(DealStateException.class, e -> assertThat(e.getCode()).isEqualTo("DEAL_FULL"));
    }

    @Test
    void joinDeal_expiredDealIsRejected() {
        Deal d = givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));
        d.setExpiresAt(NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.joinDeal(DEAL_ID, ANYA))
                .isInstanceOfSatisfying(DealStateException.class, e -> assertThat(e.getCode()).isEqualTo("DEAL_EXPIRED"));
    }

    @Test
    void joinDeal_cancelledDealIsRejected() {
        givenDeal(DealStatus.CANCELLED, 1, 4, host(DEAL_ID));

        assertThatThrownBy(() -> service.joinDeal(DEAL_ID, ANYA)).isInstanceOf(DealStateException.class);
    }

    @Test
    void joinDeal_leftUserCanJoinAgain() {
        givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID), participant("old", DEAL_ID, "anya", ParticipantStatus.FAILED));
        paymentWillReserve();

        assertThat(service.joinDeal(DEAL_ID, ANYA).getParticipant().getStatus()).isEqualTo(ParticipantStatus.RESERVED);
    }

    @Test
    void joinDeal_paymentFailureWritesNothing() {
        Deal d = givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));
        when(paymentClient.createPayment(anyString(), anyString(), any()))
                .thenThrow(new PaymentServiceException("down", new StatusRuntimeException(Status.UNAVAILABLE)));

        assertThatThrownBy(() -> service.joinDeal(DEAL_ID, ANYA)).isInstanceOf(PaymentServiceException.class);

        assertThat(d.getJoinedSlots()).isEqualTo(1);
        verify(participantRepository, never()).save(any(DealParticipant.class));
    }

    @Test
    void joinDeal_unknownDeal() {
        when(dealRepository.findByIdForUpdate("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.joinDeal("nope", ANYA)).isInstanceOf(DealNotFoundException.class);
    }

    // --- leave ----------------------------------------------------------------------

    @Test
    void leaveDeal_releasesSlotAndReopensLockedDeal() {
        Deal d = givenDeal(DealStatus.LOCKED, 4, 4, host(DEAL_ID), participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED));

        service.leaveDeal(DEAL_ID, ANYA);

        verify(paymentClient).cancelPayment("pay-anya");
        assertThat(d.getJoinedSlots()).isEqualTo(3);
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void leaveDeal_setsParticipantFailed() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.PAYMENT_PENDING);
        givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID), p);

        service.leaveDeal(DEAL_ID, ANYA);

        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.FAILED);
    }

    @Test
    void leaveDeal_afterPayingIsRejected() {
        givenDeal(DealStatus.LOCKED, 4, 4, host(DEAL_ID), participant("p2", DEAL_ID, "anya", ParticipantStatus.HELD));

        assertThatThrownBy(() -> service.leaveDeal(DEAL_ID, ANYA)).isInstanceOf(DealStateException.class);
        verify(paymentClient, never()).cancelPayment(anyString());
    }

    @Test
    void leaveDeal_notJoinedOrHostIsRejected() {
        givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));

        assertThatThrownBy(() -> service.leaveDeal(DEAL_ID, ANYA)).isInstanceOf(DealStateException.class);
        assertThatThrownBy(() -> service.leaveDeal(DEAL_ID, new CurrentUser("host-1", "Ploy S.")))
                .isInstanceOf(DealStateException.class);
    }

    @Test
    void leaveDeal_continuesWhenPaymentHasNoCancelPaymentYet() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        Deal d = givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID), p);
        when(paymentClient.cancelPayment("pay-anya")).thenThrow(
                new PaymentServiceException("no rpc", new StatusRuntimeException(Status.UNIMPLEMENTED)));

        service.leaveDeal(DEAL_ID, ANYA);

        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.FAILED);
        assertThat(d.getJoinedSlots()).isEqualTo(1);
    }

    @Test
    void leaveDeal_paymentErrorKeepsTheSlot() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        Deal d = givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID), p);
        when(paymentClient.cancelPayment("pay-anya")).thenThrow(
                new PaymentServiceException("down", new StatusRuntimeException(Status.UNAVAILABLE)));

        assertThatThrownBy(() -> service.leaveDeal(DEAL_ID, ANYA)).isInstanceOf(PaymentServiceException.class);

        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.RESERVED);
        assertThat(d.getJoinedSlots()).isEqualTo(2);
    }

    // --- cancel ---------------------------------------------------------------------

    @Test
    void cancelDeal_onlyHost() {
        givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));

        assertThatThrownBy(() -> service.cancelDeal(DEAL_ID, "anya")).isInstanceOf(DealPermissionException.class);
    }

    @Test
    void cancelDeal_notAfterCompletion() {
        givenDeal(DealStatus.COMPLETED, 4, 4, host(DEAL_ID));

        assertThatThrownBy(() -> service.cancelDeal(DEAL_ID, "host-1")).isInstanceOf(DealStateException.class);
    }

    @Test
    void cancelDeal_notOnceSomeoneConfirmedReceipt() {
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID),
                participant("p2", DEAL_ID, "narin", ParticipantStatus.RECEIVED),
                participant("p3", DEAL_ID, "anya", ParticipantStatus.HELD));

        assertThatThrownBy(() -> service.cancelDeal(DEAL_ID, "host-1")).isInstanceOf(DealStateException.class);

        assertThat(d.getStatus()).isEqualTo(DealStatus.LOCKED);
        verify(paymentClient, never()).refundDeal(anyString());
        verify(publisher, never()).publishDealCancelled(any(), any());
    }

    @Test
    void cancelDeal_tellsOnlyThePeopleWhoseMoneyIsAtPayment_afterTheRefund() {
        DealParticipant paid = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant paying = participant("p3", DEAL_ID, "mali", ParticipantStatus.PAYMENT_PENDING);
        DealParticipant reserved = participant("p4", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        DealParticipant left = participant("p5", DEAL_ID, "chai", ParticipantStatus.FAILED);
        Deal d = givenDeal(DealStatus.LOCKED, 4, 5, host(DEAL_ID), paid, paying, reserved, left);
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().setRefundedCount(2).build());

        service.cancelDeal(DEAL_ID, "host-1");

        // ids were read before the rows were rewritten to REFUNDED / FAILED; host never included
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(paymentClient, publisher);
        order.verify(paymentClient).refundDeal(DEAL_ID);
        order.verify(publisher).publishDealCancelled(d, List.of("narin", "mali"));
        verify(publisher, never()).publishDealExpired(any(), any());
    }

    @Test
    void cancelDeal_nobodyPaid_sendsAnEmptyList() {
        Deal d = givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID),
                participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED));
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().build());

        service.cancelDeal(DEAL_ID, "host-1");

        verify(publisher).publishDealCancelled(d, List.of());
    }

    @Test
    void cancelDeal_rejected_publishesNothing() {
        givenDeal(DealStatus.COMPLETED, 4, 4, host(DEAL_ID));
        assertThatThrownBy(() -> service.cancelDeal(DEAL_ID, "host-1")).isInstanceOf(DealStateException.class);

        givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID));
        assertThatThrownBy(() -> service.cancelDeal(DEAL_ID, "anya")).isInstanceOf(DealPermissionException.class);

        verify(publisher, never()).publishDealCancelled(any(), any());
    }

    @Test
    void cancelDeal_survivesBrokerOutage() {
        Deal d = givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID),
                participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD));
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().setRefundedCount(1).build());
        org.mockito.Mockito.doThrow(new AmqpIOException(new java.io.IOException("down")))
                .when(publisher).publishDealCancelled(any(), any());

        assertThat(service.cancelDeal(DEAL_ID, "host-1").getStatus()).isEqualTo(DealStatus.CANCELLED);
        assertThat(d.getStatus()).isEqualTo(DealStatus.CANCELLED);
    }

    @Test
    void cancelDeal_appliesPerParticipantRefundStatuses() {
        DealParticipant paid = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant unpaid = participant("p3", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), paid, unpaid);
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().setRefundedCount(1)
                .addPayments(PaymentStatusItem.newBuilder().setParticipantId("narin").setStatus("REFUNDED"))
                .addPayments(PaymentStatusItem.newBuilder().setParticipantId("anya").setStatus("FAILED"))
                .build());

        service.cancelDeal(DEAL_ID, "host-1");

        assertThat(d.getStatus()).isEqualTo(DealStatus.CANCELLED);
        assertThat(d.getCancelledAt()).isEqualTo(NOW);
        assertThat(paid.getStatus()).isEqualTo(ParticipantStatus.REFUNDED);
        assertThat(unpaid.getStatus()).isEqualTo(ParticipantStatus.FAILED);
    }

    @Test
    void cancelDeal_userWhoRejoined_getsTheStatusOfTheirNewPayment() {
        // narin left (old payment FAILED) and joined again (new payment HELD): both come back in payments[]
        DealParticipant rejoined = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        rejoined.setPaymentId("pay-new");
        givenDeal(DealStatus.LOCKED, 2, 2, host(DEAL_ID), rejoined);
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().setRefundedCount(1)
                .addPayments(PaymentStatusItem.newBuilder().setPaymentId("pay-new").setParticipantId("narin").setStatus("REFUNDED"))
                .addPayments(PaymentStatusItem.newBuilder().setPaymentId("pay-old").setParticipantId("narin").setStatus("FAILED"))
                .build());

        service.cancelDeal(DEAL_ID, "host-1");

        assertThat(rejoined.getStatus()).isEqualTo(ParticipantStatus.REFUNDED);
    }

    @Test
    void cancelDeal_oldPaymentWithoutPaymentsList_usesRefundedCount() {
        DealParticipant paid = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant unpaid = participant("p3", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), paid, unpaid);
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().setRefundedCount(1).build());

        service.cancelDeal(DEAL_ID, "host-1");

        assertThat(paid.getStatus()).isEqualTo(ParticipantStatus.REFUNDED);
        assertThat(unpaid.getStatus()).isEqualTo(ParticipantStatus.FAILED);
    }

    @Test
    void cancelDeal_oldPaymentRefundedFewerThanPaid_staysPending() {
        DealParticipant a = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant b = participant("p3", DEAL_ID, "anya", ParticipantStatus.HELD);
        givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), a, b);
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().setRefundedCount(1).build());

        service.cancelDeal(DEAL_ID, "host-1");

        assertThat(a.getStatus()).isEqualTo(ParticipantStatus.REFUND_PENDING);
        assertThat(b.getStatus()).isEqualTo(ParticipantStatus.REFUND_PENDING);
    }

    @Test
    void cancelDeal_paymentDownStillCancelsAndLeavesPaidRowsPending() {
        DealParticipant paid = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant unpaid = participant("p3", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), paid, unpaid);
        when(paymentClient.refundDeal(DEAL_ID)).thenThrow(
                new PaymentServiceException("down", new StatusRuntimeException(Status.UNAVAILABLE)));

        service.cancelDeal(DEAL_ID, "host-1");

        assertThat(d.getStatus()).isEqualTo(DealStatus.CANCELLED);
        assertThat(paid.getStatus()).isEqualTo(ParticipantStatus.REFUND_PENDING);
        assertThat(unpaid.getStatus()).isEqualTo(ParticipantStatus.FAILED);
    }

    // --- DG5 ------------------------------------------------------------------------

    @Test
    void updateParticipantPayment_heldIsRecordedAndIdempotent() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.PAYMENT_PENDING);
        givenDeal(DealStatus.LOCKED, 2, 2, host(DEAL_ID), p);
        when(participantRepository.findByPaymentId("pay-anya")).thenReturn(Optional.of(p));

        service.updateParticipantPayment(DEAL_ID, "anya", "pay-anya", "HELD");
        service.updateParticipantPayment(DEAL_ID, "anya", "pay-anya", "HELD");

        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.HELD);
        assertThat(p.getReservedUntil()).isNull();
    }

    @Test
    void updateParticipantPayment_failedReleasesSlot() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.PAYMENT_PENDING);
        Deal d = givenDeal(DealStatus.LOCKED, 2, 2, host(DEAL_ID), p);
        when(participantRepository.findByPaymentId("pay-anya")).thenReturn(Optional.of(p));

        service.updateParticipantPayment(DEAL_ID, "anya", "pay-anya", "FAILED");

        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.FAILED);
        assertThat(d.getJoinedSlots()).isEqualTo(1);
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void updateParticipantPayment_finishedRowsAreNeverMovedBack() {
        DealParticipant failed = participant("p2", DEAL_ID, "anya", ParticipantStatus.FAILED);
        Deal d = givenDeal(DealStatus.ACTIVE, 1, 2, host(DEAL_ID), failed);
        when(participantRepository.findByPaymentId("pay-anya")).thenReturn(Optional.of(failed));

        service.updateParticipantPayment(DEAL_ID, "anya", "pay-anya", "HELD");
        service.updateParticipantPayment(DEAL_ID, "anya", "pay-anya", "FAILED");

        assertThat(failed.getStatus()).isEqualTo(ParticipantStatus.FAILED);
        assertThat(d.getJoinedSlots()).isEqualTo(1); // no second decrement
    }

    @Test
    void updateParticipantPayment_heldOnCancelledDealBecomesRefundPending() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.PAYMENT_PENDING);
        givenDeal(DealStatus.CANCELLED, 2, 2, host(DEAL_ID), p);
        when(participantRepository.findByPaymentId("pay-anya")).thenReturn(Optional.of(p));

        service.updateParticipantPayment(DEAL_ID, "anya", "pay-anya", "HELD");

        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.REFUND_PENDING);
    }

    @Test
    void updateParticipantPayment_findsParticipantByUserWhenNoPaymentId() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID), p);

        Deal d = service.updateParticipantPayment(DEAL_ID, "anya", "", "PAYMENT_PENDING");

        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.PAYMENT_PENDING);
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void updateParticipantPayment_unknownParticipantAndBadStatus() {
        givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));

        assertThatThrownBy(() -> service.updateParticipantPayment(DEAL_ID, "ghost", "", "HELD"))
                .isInstanceOf(ParticipantNotFoundException.class);
        assertThatThrownBy(() -> service.updateParticipantPayment(DEAL_ID, "anya", "", "REFUNDED"))
                .isInstanceOf(DealValidationException.class);
    }

    // --- schedulers -----------------------------------------------------------------

    @Test
    void expireReservation_releasesLateReservation() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        p.setReservedUntil(NOW.minusSeconds(1));
        Deal d = givenDeal(DealStatus.LOCKED, 2, 2, host(DEAL_ID), p);
        when(participantRepository.findById("p2")).thenReturn(Optional.of(p));

        assertThat(service.expireReservation(DEAL_ID, "p2")).isTrue();

        verify(paymentClient).cancelPayment("pay-anya");
        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.FAILED);
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void expireReservation_skipsWhenPaymentSaysItAlreadyMovedOn() {
        DealParticipant p = participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        p.setReservedUntil(NOW.minusSeconds(1));
        givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID), p);
        when(participantRepository.findById("p2")).thenReturn(Optional.of(p));
        when(paymentClient.cancelPayment("pay-anya")).thenThrow(
                new PaymentServiceException("paid", new StatusRuntimeException(Status.FAILED_PRECONDITION)));

        assertThat(service.expireReservation(DEAL_ID, "p2")).isFalse();
        assertThat(p.getStatus()).isEqualTo(ParticipantStatus.RESERVED);
    }

    @Test
    void expireReservation_ignoresStillValidOrAlreadyPaid() {
        DealParticipant valid = participant("p2", DEAL_ID, "anya", ParticipantStatus.RESERVED); // reserved until NOW+300
        givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID), valid);
        when(participantRepository.findById("p2")).thenReturn(Optional.of(valid));

        assertThat(service.expireReservation(DEAL_ID, "p2")).isFalse();
        verify(paymentClient, never()).cancelPayment(anyString());
    }

    @Test
    void expireDeal_marksExpiredAndRefunds() {
        DealParticipant paid = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        Deal d = givenDeal(DealStatus.ACTIVE, 2, 4, host(DEAL_ID), paid);
        d.setExpiresAt(NOW.minusSeconds(1));
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder()
                .addPayments(PaymentStatusItem.newBuilder().setParticipantId("narin").setStatus("REFUNDED")).build());

        assertThat(service.expireDeal(DEAL_ID)).isTrue();

        assertThat(d.getStatus()).isEqualTo(DealStatus.EXPIRED);
        assertThat(paid.getStatus()).isEqualTo(ParticipantStatus.REFUNDED);
    }

    @Test
    void expireDeal_tellsTheParticipantsWhoPaid() {
        DealParticipant paid = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant reserved = participant("p3", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), paid, reserved);
        d.setExpiresAt(NOW.minusSeconds(1));
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().setRefundedCount(1).build());

        assertThat(service.expireDeal(DEAL_ID)).isTrue();

        verify(publisher).publishDealExpired(d, List.of("narin"));
        verify(publisher, never()).publishDealCancelled(any(), any());
    }

    @Test
    void expireDeal_survivesBrokerOutage() {
        Deal d = givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));
        d.setExpiresAt(NOW.minusSeconds(1));
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().build());
        org.mockito.Mockito.doThrow(new AmqpIOException(new java.io.IOException("down")))
                .when(publisher).publishDealExpired(any(), any());

        assertThat(service.expireDeal(DEAL_ID)).isTrue();
        assertThat(d.getStatus()).isEqualTo(DealStatus.EXPIRED);
    }

    @Test
    void expireDeal_fullyPaidDealDoesNotExpire() {
        DealParticipant paid = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant received = participant("p3", DEAL_ID, "anya", ParticipantStatus.RECEIVED);
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), paid, received);
        d.setExpiresAt(NOW.minusSeconds(1));

        assertThat(service.expireDeal(DEAL_ID)).isFalse();

        assertThat(d.getStatus()).isEqualTo(DealStatus.LOCKED);
        assertThat(paid.getStatus()).isEqualTo(ParticipantStatus.HELD);
        verify(paymentClient, never()).refundDeal(anyString());
        verify(publisher, never()).publishDealExpired(any(), any());
    }

    @Test
    void expireDeal_lockedButSomeoneUnpaid_stillExpires() {
        DealParticipant paid = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant reserved = participant("p3", DEAL_ID, "anya", ParticipantStatus.RESERVED);
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), paid, reserved);
        d.setExpiresAt(NOW.minusSeconds(1));
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder().setRefundedCount(1).build());

        assertThat(service.expireDeal(DEAL_ID)).isTrue();
        assertThat(d.getStatus()).isEqualTo(DealStatus.EXPIRED);
    }

    @Test
    void expireDeal_notYetExpiredIsLeftAlone() {
        Deal d = givenDeal(DealStatus.ACTIVE, 1, 4, host(DEAL_ID));

        assertThat(service.expireDeal(DEAL_ID)).isFalse();
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
        verify(paymentClient, never()).refundDeal(anyString());
        verify(publisher, never()).publishDealExpired(any(), any());
    }

    @Test
    void retryRefund_onlyTouchesRefundPendingRows() {
        DealParticipant pending = participant("p2", DEAL_ID, "narin", ParticipantStatus.REFUND_PENDING);
        DealParticipant done = participant("p3", DEAL_ID, "anya", ParticipantStatus.REFUNDED);
        givenDeal(DealStatus.CANCELLED, 3, 3, host(DEAL_ID), pending, done);
        when(paymentClient.refundDeal(DEAL_ID)).thenReturn(RefundDealResponse.newBuilder()
                .addPayments(PaymentStatusItem.newBuilder().setParticipantId("narin").setStatus("REFUNDED")).build());

        service.retryRefund(DEAL_ID);

        assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.REFUNDED);
        assertThat(done.getStatus()).isEqualTo(ParticipantStatus.REFUNDED);
    }

    @Test
    void retryRefund_stillDownStaysPending() {
        DealParticipant pending = participant("p2", DEAL_ID, "narin", ParticipantStatus.REFUND_PENDING);
        givenDeal(DealStatus.CANCELLED, 2, 2, host(DEAL_ID), pending);
        when(paymentClient.refundDeal(DEAL_ID)).thenThrow(
                new PaymentServiceException("down", new StatusRuntimeException(Status.UNAVAILABLE)));

        service.retryRefund(DEAL_ID);

        assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.REFUND_PENDING);
    }

    @Test
    void cancelPaymentResponseTypeIsUsedByTheClient() {
        // keeps the generated PG2 message on the test classpath
        assertThat(CancelPaymentResponse.getDefaultInstance().getStatus()).isEmpty();
    }

    // --- DR9 confirm receipt / SCH4 payout ------------------------------------------------

    private static final CurrentUser NARIN = new CurrentUser("narin", "Narin K.");

    @Test
    void confirmReceipt_lastParticipant_paysTheHostAndCompletesTheDeal() {
        DealParticipant narin = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        DealParticipant anya = participant("p3", DEAL_ID, "anya", ParticipantStatus.RECEIVED);
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), narin, anya);
        when(paymentClient.releaseFunds(DEAL_ID, "host-1"))
                .thenReturn(com.harnkan.deal.grpc.payment.ReleaseFundsResponse.newBuilder().setReleasedCount(2).build());

        service.confirmReceipt(DEAL_ID, NARIN);

        verify(paymentClient).confirmReceipt("pay-narin", "narin");
        assertThat(narin.getStatus()).isEqualTo(ParticipantStatus.RECEIVED);
        assertThat(narin.getReceivedAt()).isEqualTo(NOW);
        assertThat(d.getStatus()).isEqualTo(DealStatus.COMPLETED);
        assertThat(d.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void confirmReceipt_notTheLast_doesNotPayOutYet() {
        DealParticipant narin = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), narin,
                participant("p3", DEAL_ID, "anya", ParticipantStatus.HELD));

        service.confirmReceipt(DEAL_ID, NARIN);

        assertThat(narin.getStatus()).isEqualTo(ParticipantStatus.RECEIVED);
        assertThat(d.getStatus()).isEqualTo(DealStatus.LOCKED);
        verify(paymentClient, never()).releaseFunds(anyString(), anyString());
    }

    @Test
    void confirmReceipt_beforeEveryonePaid_isNotReady() {
        DealParticipant narin = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), narin,
                participant("p3", DEAL_ID, "anya", ParticipantStatus.RESERVED));

        assertThatThrownBy(() -> service.confirmReceipt(DEAL_ID, NARIN))
                .isInstanceOfSatisfying(DealStateException.class, e -> assertThat(e.getCode()).isEqualTo("DEAL_NOT_READY"));
        assertThat(narin.getStatus()).isEqualTo(ParticipantStatus.HELD);
        verify(paymentClient, never()).confirmReceipt(anyString(), anyString());
    }

    @Test
    void confirmReceipt_hostOrOutsider_isForbidden() {
        givenDeal(DealStatus.LOCKED, 2, 2, host(DEAL_ID), participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD));

        assertThatThrownBy(() -> service.confirmReceipt(DEAL_ID, new CurrentUser("host-1", "Ploy S.")))
                .isInstanceOf(DealPermissionException.class);
        assertThatThrownBy(() -> service.confirmReceipt(DEAL_ID, new CurrentUser("stranger", "S")))
                .isInstanceOf(DealPermissionException.class);
    }

    @Test
    void confirmReceipt_again_changesNothing() {
        DealParticipant narin = participant("p2", DEAL_ID, "narin", ParticipantStatus.RECEIVED);
        givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID), narin,
                participant("p3", DEAL_ID, "anya", ParticipantStatus.HELD));

        service.confirmReceipt(DEAL_ID, NARIN);

        verify(paymentClient, never()).confirmReceipt(anyString(), anyString());
    }

    @Test
    void confirmReceipt_paymentDown_changesNothing() {
        DealParticipant narin = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        givenDeal(DealStatus.LOCKED, 2, 2, host(DEAL_ID), narin);
        when(paymentClient.confirmReceipt("pay-narin", "narin")).thenThrow(
                new PaymentServiceException("down", new StatusRuntimeException(Status.UNAVAILABLE)));

        assertThatThrownBy(() -> service.confirmReceipt(DEAL_ID, NARIN)).isInstanceOf(PaymentServiceException.class);
        assertThat(narin.getStatus()).isEqualTo(ParticipantStatus.HELD);
    }

    @Test
    void confirmReceipt_payoutFails_dealStaysLockedForSch4() {
        DealParticipant narin = participant("p2", DEAL_ID, "narin", ParticipantStatus.HELD);
        Deal d = givenDeal(DealStatus.LOCKED, 2, 2, host(DEAL_ID), narin);
        when(paymentClient.releaseFunds(DEAL_ID, "host-1")).thenThrow(
                new PaymentServiceException("down", new StatusRuntimeException(Status.UNAVAILABLE)));

        service.confirmReceipt(DEAL_ID, NARIN);

        assertThat(narin.getStatus()).isEqualTo(ParticipantStatus.RECEIVED);
        assertThat(d.getStatus()).isEqualTo(DealStatus.LOCKED);
    }

    @Test
    void retryPayout_onlyWhenEveryoneReceived() {
        Deal d = givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID),
                participant("p2", DEAL_ID, "narin", ParticipantStatus.RECEIVED),
                participant("p3", DEAL_ID, "anya", ParticipantStatus.HELD));
        assertThat(service.retryPayout(DEAL_ID)).isFalse();
        verify(paymentClient, never()).releaseFunds(anyString(), anyString());

        givenDeal(DealStatus.LOCKED, 3, 3, host(DEAL_ID),
                participant("p2", DEAL_ID, "narin", ParticipantStatus.RECEIVED),
                participant("p3", DEAL_ID, "anya", ParticipantStatus.RECEIVED));
        assertThat(service.retryPayout(DEAL_ID)).isTrue();
        verify(paymentClient).releaseFunds(DEAL_ID, "host-1");
    }
}
