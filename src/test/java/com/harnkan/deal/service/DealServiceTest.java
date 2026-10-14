package com.harnkan.deal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;

import com.harnkan.deal.client.PaymentServiceClient;
import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.grpc.payment.CreatePaymentResponse;
import com.harnkan.deal.grpc.payment.RefundDealResponse;
import com.harnkan.deal.messaging.DealEventPublisher;
import com.harnkan.deal.repository.DealRepository;

/**
 * Covers the core business logic behind DR2/DG1 (create), DR4/DG2 (get), DR7 (join -> PG1
 * CreatePayment), DR6/DG4 (cancel -> PG4 RefundDeal), DG5 (confirmSlot) and DG6 (releaseSlot) —
 * independent of whether the caller is REST or gRPC.
 */
@ExtendWith(MockitoExtension.class)
class DealServiceTest {

    @Mock
    private DealRepository dealRepository;

    @Mock
    private PaymentServiceClient paymentServiceClient;

    @Mock
    private DealEventPublisher dealEventPublisher;

    private DealService dealService;

    @BeforeEach
    void setUp() {
        dealService = new DealService(dealRepository, paymentServiceClient, dealEventPublisher);
    }

    @Test
    void createDeal_savesActiveDealWithZeroJoinedSlots() {
        when(dealRepository.save(any())).thenAnswer(invocation -> {
            Deal d = invocation.getArgument(0);
            d.setId("generated-id");
            return d;
        });

        Deal deal = dealService.createDeal(CreateDealCommand.builder()
                .creatorId("creator-1")
                .shopName("Coffee Corner")
                .promotionDetail("Buy 1 Get 1")
                .category("DRINK")
                .pricePerSlot(new BigDecimal("49.00"))
                .targetSlots(3)
                .latitude(new BigDecimal("13.75630000"))
                .longitude(new BigDecimal("100.50180000"))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build());

        assertThat(deal.getId()).isEqualTo("generated-id");
        assertThat(deal.getStatus()).isEqualTo(DealStatus.ACTIVE);
        assertThat(deal.getJoinedSlots()).isZero();
        verify(dealEventPublisher).publishDealCreated(deal);
    }

    @Test
    void createDeal_stillSucceedsWhenBrokerIsDown() {
        when(dealRepository.save(any())).thenAnswer(invocation -> {
            Deal d = invocation.getArgument(0);
            d.setId("generated-id");
            return d;
        });
        doThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")))
                .when(dealEventPublisher).publishDealCreated(any());

        Deal deal = dealService.createDeal(validCreateCommand());

        assertThat(deal.getId()).isEqualTo("generated-id");
        assertThat(deal.getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void createDeal_doesNotPublishWhenValidationFails() {
        CreateDealCommand invalid = CreateDealCommand.builder().creatorId("creator-1").build();

        assertThatThrownBy(() -> dealService.createDeal(invalid)).isInstanceOf(DealValidationException.class);
        verify(dealEventPublisher, never()).publishDealCreated(any());
    }

    private static CreateDealCommand validCreateCommand() {
        return CreateDealCommand.builder()
                .creatorId("creator-1")
                .shopName("Coffee Corner")
                .promotionDetail("Buy 1 Get 1")
                .category("DRINK")
                .pricePerSlot(new BigDecimal("49.00"))
                .targetSlots(3)
                .latitude(new BigDecimal("13.75630000"))
                .longitude(new BigDecimal("100.50180000"))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    @Test
    void createDeal_rejectsInvalidTargetSlots() {
        CreateDealCommand cmd = CreateDealCommand.builder()
                .creatorId("creator-1")
                .shopName("Coffee Corner")
                .promotionDetail("Buy 1 Get 1")
                .category("DRINK")
                .pricePerSlot(new BigDecimal("49.00"))
                .targetSlots(0)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();

        assertThatThrownBy(() -> dealService.createDeal(cmd)).isInstanceOf(DealValidationException.class);
    }

    @Test
    void getDeal_throwsWhenMissing() {
        when(dealRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dealService.getDeal("missing")).isInstanceOf(DealNotFoundException.class);
    }

    @Test
    void joinDeal_incrementsSlotAndCallsCreatePayment() {
        Deal existing = activeDeal("deal-1", "creator-1", 3, 1);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));
        when(dealRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime reservedUntil = LocalDateTime.now().plusMinutes(5).withNano(0);
        when(paymentServiceClient.createPayment(eq("deal-1"), eq("user-2"), any(BigDecimal.class)))
                .thenReturn(CreatePaymentResponse.newBuilder()
                        .setPaymentId("pay-1")
                        .setStatus("RESERVED")
                        .setReservedUntil(reservedUntil.toString())
                        .build());

        JoinDealResult result = dealService.joinDeal("deal-1", "user-2");

        assertThat(result.getPaymentId()).isEqualTo("pay-1");
        assertThat(result.getDeal().getJoinedSlots()).isEqualTo(2);
        assertThat(result.getDeal().getStatus()).isEqualTo(DealStatus.ACTIVE);
        assertThat(result.getReservedUntil()).isEqualTo(reservedUntil);
        verify(paymentServiceClient).createPayment("deal-1", "user-2", existing.getPricePerSlot());
    }

    @Test
    void joinDeal_locksDealWhenLastSlotTaken() {
        Deal existing = activeDeal("deal-1", "creator-1", 2, 1);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));
        when(dealRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentServiceClient.createPayment(anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn(CreatePaymentResponse.newBuilder()
                        .setPaymentId("pay-1")
                        .setReservedUntil(LocalDateTime.now().plusMinutes(5).toString())
                        .build());

        JoinDealResult result = dealService.joinDeal("deal-1", "user-2");

        assertThat(result.getDeal().getJoinedSlots()).isEqualTo(2);
        assertThat(result.getDeal().getStatus()).isEqualTo(DealStatus.LOCKED);
    }

    @Test
    void joinDeal_rejectsWhenAlreadyFull() {
        Deal existing = activeDeal("deal-1", "creator-1", 2, 2);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> dealService.joinDeal("deal-1", "user-3")).isInstanceOf(DealStateException.class);
        verify(paymentServiceClient, never()).createPayment(anyString(), anyString(), any());
    }

    @Test
    void joinDeal_rollsBackSlotWhenPaymentServiceFails() {
        Deal existing = activeDeal("deal-1", "creator-1", 3, 1);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));
        ArgumentCaptor<Deal> captor = ArgumentCaptor.forClass(Deal.class);
        when(dealRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentServiceClient.createPayment(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new RuntimeException("Payment Service unreachable"));

        assertThatThrownBy(() -> dealService.joinDeal("deal-1", "user-2")).isInstanceOf(RuntimeException.class);

        // last save() call must have rolled the slot back to its original count
        assertThat(captor.getValue().getJoinedSlots()).isEqualTo(1);
        assertThat(captor.getValue().getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void cancelDeal_rejectsNonCreator() {
        Deal existing = activeDeal("deal-1", "creator-1", 3, 1);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> dealService.cancelDeal("deal-1", "someone-else"))
                .isInstanceOf(DealPermissionException.class);
        verify(paymentServiceClient, never()).refundDeal(anyString());
    }

    @Test
    void cancelDeal_cancelsAndTriggersRefund() {
        Deal existing = activeDeal("deal-1", "creator-1", 3, 1);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));
        when(dealRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentServiceClient.refundDeal("deal-1"))
                .thenReturn(RefundDealResponse.newBuilder().setRefundedCount(1).build());

        Deal cancelled = dealService.cancelDeal("deal-1", "creator-1");

        assertThat(cancelled.getStatus()).isEqualTo(DealStatus.CANCELLED);
        verify(paymentServiceClient).refundDeal("deal-1");
    }

    @Test
    void cancelDeal_stillSucceedsWhenRefundDealFails() {
        // Regression test: the deal is already cancelled (saved) before RefundDeal is called,
        // so a broken/unreachable Payment Service must not turn an already-successful cancel
        // into an error — it's best-effort and should just be logged.
        Deal existing = activeDeal("deal-1", "creator-1", 3, 1);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));
        when(dealRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentServiceClient.refundDeal("deal-1"))
                .thenThrow(new com.harnkan.deal.exception.PaymentServiceException("boom", new RuntimeException()));

        Deal cancelled = dealService.cancelDeal("deal-1", "creator-1");

        assertThat(cancelled.getStatus()).isEqualTo(DealStatus.CANCELLED);
    }

    @Test
    void confirmSlot_doesNotChangeJoinedSlots() {
        Deal existing = activeDeal("deal-1", "creator-1", 3, 1);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));

        Deal result = dealService.confirmSlot("deal-1");

        assertThat(result.getJoinedSlots()).isEqualTo(1);
        verify(dealRepository, never()).save(any());
    }

    @Test
    void releaseSlot_decrementsAndUnlocks() {
        Deal existing = activeDeal("deal-1", "creator-1", 2, 2);
        existing.setStatus(DealStatus.LOCKED);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));
        when(dealRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Deal result = dealService.releaseSlot("deal-1");

        assertThat(result.getJoinedSlots()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void releaseSlot_neverGoesBelowZero() {
        Deal existing = activeDeal("deal-1", "creator-1", 2, 0);
        when(dealRepository.findById("deal-1")).thenReturn(Optional.of(existing));
        when(dealRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Deal result = dealService.releaseSlot("deal-1");

        assertThat(result.getJoinedSlots()).isZero();
    }

    private static Deal activeDeal(String id, String creatorId, int targetSlots, int joinedSlots) {
        return Deal.builder()
                .id(id)
                .creatorId(creatorId)
                .shopName("Coffee Corner")
                .promotionDetail("Buy 1 Get 1")
                .category("DRINK")
                .pricePerSlot(new BigDecimal("49.00"))
                .targetSlots(targetSlots)
                .joinedSlots(joinedSlots)
                .latitude(new BigDecimal("13.75630000"))
                .longitude(new BigDecimal("100.50180000"))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .status(DealStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
