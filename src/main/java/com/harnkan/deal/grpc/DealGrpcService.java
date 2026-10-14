package com.harnkan.deal.grpc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;

import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.DealValidationException;
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
        run(responseObserver, () -> dealService.createDeal(CreateDealCommand.builder()
                .creatorId(request.getCreatorId())
                .shopName(request.getShopName())
                .promotionDetail(request.getPromotionDetail())
                .category(request.getCategory())
                .menuImageUrl(request.getMenuImageUrl().isBlank() ? null : request.getMenuImageUrl())
                .pricePerSlot(parseDecimal(request.getPricePerSlot(), "price_per_slot"))
                .targetSlots(request.getTargetSlots())
                .latitude(BigDecimal.valueOf(request.getLatitude()))
                .longitude(BigDecimal.valueOf(request.getLongitude()))
                .expiresAt(parseIsoUtc(request.getExpiresAt(), "expires_at"))
                .build()));
    }

    @Override
    public void getDeal(GetDealRequest request, StreamObserver<DealResponse> responseObserver) {
        run(responseObserver, () -> dealService.getDeal(request.getDealId()));
    }

    @Override
    public void cancelDeal(CancelDealRequest request, StreamObserver<DealResponse> responseObserver) {
        run(responseObserver, () -> dealService.cancelDeal(request.getDealId(), request.getRequesterId()));
    }

    @Override
    public void confirmSlot(ConfirmSlotRequest request, StreamObserver<DealResponse> responseObserver) {
        run(responseObserver, () -> dealService.confirmSlot(request.getDealId()));
    }

    @Override
    public void releaseSlot(ReleaseSlotRequest request, StreamObserver<DealResponse> responseObserver) {
        run(responseObserver, () -> dealService.releaseSlot(request.getDealId()));
    }

    // --- helpers -----------------------------------------------------------------

    private void run(StreamObserver<DealResponse> responseObserver, java.util.function.Supplier<com.harnkan.deal.domain.Deal> action) {
        try {
            com.harnkan.deal.domain.Deal deal = action.get();
            responseObserver.onNext(DealResponse.newBuilder().setDeal(toProto(deal)).build());
            responseObserver.onCompleted();
        } catch (DealValidationException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (DealNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (DealPermissionException e) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
        } catch (DealStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private Deal toProto(com.harnkan.deal.domain.Deal deal) {
        Deal.Builder builder = Deal.newBuilder()
                .setId(deal.getId())
                .setCreatorId(deal.getCreatorId())
                .setShopName(deal.getShopName())
                .setPromotionDetail(deal.getPromotionDetail())
                .setCategory(deal.getCategory())
                .setPricePerSlot(deal.getPricePerSlot().toPlainString())
                .setTargetSlots(deal.getTargetSlots())
                .setJoinedSlots(deal.getJoinedSlots())
                .setLatitude(deal.getLatitude().doubleValue())
                .setLongitude(deal.getLongitude().doubleValue())
                .setExpiresAt(toIsoUtc(deal.getExpiresAt()))
                .setStatus(deal.getStatus().name())
                .setCreatedAt(toIsoUtc(deal.getCreatedAt()))
                .setUpdatedAt(toIsoUtc(deal.getUpdatedAt()));
        if (deal.getMenuImageUrl() != null) {
            builder.setMenuImageUrl(deal.getMenuImageUrl());
        }
        return builder.build();
    }

    private static BigDecimal parseDecimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new DealValidationException(field + " is not a valid decimal: " + value);
        }
    }

    private static LocalDateTime parseIsoUtc(String value, String field) {
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new DealValidationException(
                    field + " must be ISO-8601 UTC, e.g. 2026-09-29T12:00:00Z, got: " + value);
        }
    }

    private static String toIsoUtc(LocalDateTime value) {
        return value.atOffset(ZoneOffset.UTC).toInstant().toString();
    }
}
