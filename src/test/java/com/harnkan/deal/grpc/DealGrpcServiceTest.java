package com.harnkan.deal.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
                        .setShopName("Coffee Corner")
                        .setPromotionDetail("Buy 1 Get 1")
                        .setCategory("DRINK")
                        .setPricePerSlot("49.00")
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
    void confirmSlot_mapsStateExceptionToFailedPreconditionStatus() {
        when(dealService.confirmSlot("deal-1")).thenThrow(new DealStateException("Deal is already full"));

        TestObserver<DealResponse> observer = new TestObserver<>();
        grpcService.confirmSlot(
                ConfirmSlotRequest.newBuilder().setDealId("deal-1").setParticipantId("user-2").build(), observer);

        assertGrpcStatus(observer, Status.FAILED_PRECONDITION);
    }

    private static void assertGrpcStatus(TestObserver<DealResponse> observer, Status expected) {
        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) observer.error).getStatus().getCode()).isEqualTo(expected.getCode());
    }

    private static com.harnkan.deal.domain.Deal sampleDeal() {
        return com.harnkan.deal.domain.Deal.builder()
                .id("deal-1")
                .creatorId("creator-1")
                .shopName("Coffee Corner")
                .promotionDetail("Buy 1 Get 1")
                .category("DRINK")
                .pricePerSlot(new BigDecimal("49.00"))
                .targetSlots(3)
                .joinedSlots(1)
                .latitude(new BigDecimal("13.75630000"))
                .longitude(new BigDecimal("100.50180000"))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .status(DealStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
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
