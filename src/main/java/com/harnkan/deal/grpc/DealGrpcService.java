package com.harnkan.deal.grpc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.exception.ParticipantNotFoundException;
import com.harnkan.deal.service.CreateDealCommand;
import com.harnkan.deal.service.DealService;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DealGrpcService extends DealServiceGrpc.DealServiceImplBase {

    private final DealService dealService;

    @Override
    public void createDeal(CreateDealRequest request, StreamObserver<DealResponse> responseObserver) {
        reply(responseObserver, () -> toResponse(dealService.createDeal(CreateDealCommand.builder()
                .creatorId(request.getCreatorId())
                .creatorName(request.getCreatorName())
                .dealName(request.getDealName())
                .details(request.getDetails().isBlank() ? null : request.getDetails())
                .category(request.getCategory())
                .menuImageUrl(request.getMenuImageUrl().isBlank() ? null : request.getMenuImageUrl())
                .pickupLocation(request.getPickupLocation())
                .totalPrice(parseDecimal(request.getTotalPrice(), "total_price"))
                .targetSlots(request.getTargetSlots())
                .latitude(BigDecimal.valueOf(request.getLatitude()))
                .longitude(BigDecimal.valueOf(request.getLongitude()))
                .expiresAt(parseIsoUtc(request.getExpiresAt(), "expires_at"))
                .build())));
    }

    @Override
    public void getDeal(GetDealRequest request, StreamObserver<DealResponse> responseObserver) {
        reply(responseObserver, () -> toResponse(dealService.getDeal(request.getDealId())));
    }

    @Override
    public void cancelDeal(CancelDealRequest request, StreamObserver<DealResponse> responseObserver) {
        reply(responseObserver,
                () -> toResponse(dealService.cancelDeal(request.getDealId(), request.getRequesterId())));
    }

    @Override
    public void updateParticipantPayment(UpdateParticipantPaymentRequest request,
            StreamObserver<UpdateParticipantPaymentResponse> responseObserver) {
        reply(responseObserver, () -> UpdateParticipantPaymentResponse.newBuilder()
                .setDealStatus(dealService.updateParticipantPayment(request.getDealId(), request.getParticipantId(),
                        request.getPaymentId(), request.getStatus()).getStatus().name())
                .build());
    }

    // --- helpers -----------------------------------------------------------------

    private <T> void reply(StreamObserver<T> responseObserver, Supplier<T> action) {
        try {
            T response = action.get();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (DealValidationException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (DealNotFoundException | ParticipantNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (DealPermissionException e) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
        } catch (DealStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private static DealResponse toResponse(com.harnkan.deal.domain.Deal deal) {
        Deal.Builder builder = Deal.newBuilder()
                .setId(deal.getId())
                .setCreatorId(deal.getCreatorId())
                .setCreatorName(deal.getCreatorName())
                .setDealName(deal.getDealName())
                .setCategory(deal.getCategory())
                .setPickupLocation(deal.getPickupLocation())
                .setTotalPrice(deal.getTotalPrice().toPlainString())
                .setPricePerSlot(deal.getPricePerSlot().toPlainString())
                .setTargetSlots(deal.getTargetSlots())
                .setJoinedSlots(deal.getJoinedSlots())
                .setLatitude(deal.getLatitude().doubleValue())
                .setLongitude(deal.getLongitude().doubleValue())
                .setExpiresAt(deal.getExpiresAt().toString())
                .setStatus(deal.getStatus().name())
                .setCreatedAt(deal.getCreatedAt().toString())
                .setUpdatedAt(deal.getUpdatedAt().toString());
        if (deal.getDetails() != null) {
            builder.setDetails(deal.getDetails());
        }
        if (deal.getMenuImageUrl() != null) {
            builder.setMenuImageUrl(deal.getMenuImageUrl());
        }
        return DealResponse.newBuilder().setDeal(builder).build();
    }

    private static BigDecimal parseDecimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new DealValidationException(field + " is not a valid decimal: " + value);
        }
    }

    private static Instant parseIsoUtc(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new DealValidationException(
                    field + " must be ISO-8601 UTC, e.g. 2026-09-29T12:00:00Z, got: " + value);
        }
    }
}
