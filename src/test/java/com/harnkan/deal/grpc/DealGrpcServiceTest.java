package com.harnkan.deal.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.exception.ParticipantNotFoundException;
import com.harnkan.deal.service.DealService;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * DealGrpcService is a thin adapter now — all business logic lives in {@link DealService}
 * (see {@code DealServiceTest}). This just checks the proto <-> domain mapping and that domain
 * exceptions land on the right gRPC {@link Status} code.
 */
@ExtendWith(MockitoExtension.class)
class DealGrpcServiceTest {

    @Mock
    private DealService dealService;

    private DealGrpcService grpcService;

    @BeforeEach
    void setUp() {
        grpcService = new DealGrpcService(dealService);
    }

    @Test
    void getDeal_returnsDealOnSuccess() {
        when(dealService.getDeal("deal-1")).thenReturn(sampleDeal());

        TestObserver<DealResponse> observer = new TestObserver<>();
        grpcService.getDeal(GetDealRequest.newBuilder().setDealId("deal-1").build(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.value.getDeal().getId()).isEqualTo("deal-1");
        assertThat(observer.value.getDeal().getStatus()).isEqualTo(DealStatus.ACTIVE.name());
    }

    @Test
    void getDeal_mapsNotFoundToNotFoundStatus() {
        when(dealService.getDeal("missing")).thenThrow(new DealNotFoundException("missing"));

        TestObserver<DealResponse> observer = new TestObserver<>();
        grpcService.getDeal(GetDealRequest.newBuilder().setDealId("missing").build(), observer);

        assertGrpcStatus(observer, Status.NOT_FOUND);
    }

    @Test
    void createDeal_mapsValidationToInvalidArgumentStatus() {
        when(dealService.createDeal(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DealValidationException("target_slots must be >= 1"));

        TestObserver<DealResponse> observer = new TestObserver<>();
        grpcService.createDeal(
                CreateDealRequest.newBuilder()
                        .setCreatorId("creator-1")
                        .setCreatorName("Ploy S.")
                        .setDealName("Coffee Corner")
                        .setDetails("Buy 1 Get 1")
                        .setCategory("DRINK")
                        .setPickupLocation("Ari")
                        .setTotalPrice("147.00")
                        .setTargetSlots(0)
                        .setExpiresAt("2026-09-30T12:00:00Z")
                        .build(),
                observer);

        assertGrpcStatus(observer, Status.INVALID_ARGUMENT);
    }

    @Test
    void cancelDeal_mapsPermissionToPermissionDeniedStatus() {
        when(dealService.cancelDeal("deal-1", "someone-else"))
                .thenThrow(new DealPermissionException("Only the deal creator can cancel this deal"));

        TestObserver<DealResponse> observer = new TestObserver<>();
        grpcService.cancelDeal(
                CancelDealRequest.newBuilder().setDealId("deal-1").setRequesterId("someone-else").build(), observer);

        assertGrpcStatus(observer, Status.PERMISSION_DENIED);
    }

    @Test
    void cancelDeal_mapsStateExceptionToFailedPreconditionStatus() {
        when(dealService.cancelDeal("deal-1", "creator-1")).thenThrow(new DealStateException("already cancelled"));

        TestObserver<DealResponse> observer = new TestObserver<>();
        grpcService.cancelDeal(
                CancelDealRequest.newBuilder().setDealId("deal-1").setRequesterId("creator-1").build(), observer);

        assertGrpcStatus(observer, Status.FAILED_PRECONDITION);
    }

    @Test
    void updateParticipantPayment_returnsDealStatus() {
        when(dealService.updateParticipantPayment("deal-1", "user-2", "pay-1", "HELD")).thenReturn(sampleDeal());

        TestObserver<UpdateParticipantPaymentResponse> observer = new TestObserver<>();
        grpcService.updateParticipantPayment(UpdateParticipantPaymentRequest.newBuilder()
                .setDealId("deal-1").setParticipantId("user-2").setPaymentId("pay-1").setStatus("HELD").build(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.value.getDealStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void updateParticipantPayment_mapsUnknownParticipantToNotFound() {
        when(dealService.updateParticipantPayment("deal-1", "ghost", "", "HELD"))
                .thenThrow(new ParticipantNotFoundException("no such participant"));

        TestObserver<UpdateParticipantPaymentResponse> observer = new TestObserver<>();
        grpcService.updateParticipantPayment(UpdateParticipantPaymentRequest.newBuilder()
                .setDealId("deal-1").setParticipantId("ghost").setStatus("HELD").build(), observer);

        assertGrpcStatus(observer, Status.NOT_FOUND);
    }

    private static void assertGrpcStatus(TestObserver<?> observer, Status expected) {
        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) observer.error).getStatus().getCode()).isEqualTo(expected.getCode());
    }

    private static com.harnkan.deal.domain.Deal sampleDeal() {
        return com.harnkan.deal.domain.Deal.builder()
                .id("deal-1")
                .creatorId("creator-1")
                .creatorName("Ploy S.")
                .dealName("Coffee Corner")
                .details("Buy 1 Get 1")
                .category("DRINK")
                .pickupLocation("Ari")
                .totalPrice(new BigDecimal("147.00"))
                .pricePerSlot(new BigDecimal("49.00"))
                .targetSlots(3)
                .joinedSlots(1)
                .latitude(new BigDecimal("13.75630000"))
                .longitude(new BigDecimal("100.50180000"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .status(DealStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    /** Minimal StreamObserver that just records the terminal outcome. */
    private static final class TestObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            // no-op
        }
    }
}
