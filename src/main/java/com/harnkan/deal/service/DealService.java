package com.harnkan.deal.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.harnkan.deal.client.PaymentServiceClient;
import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.exception.PaymentServiceException;
import com.harnkan.deal.grpc.payment.CreatePaymentResponse;
import com.harnkan.deal.repository.DealRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DealService {

    private final DealRepository dealRepository;
    private final PaymentServiceClient paymentServiceClient;

    public Deal createDeal(CreateDealCommand cmd) {
        requireNonBlank(cmd.getCreatorId(), "creator_id");
        requireNonBlank(cmd.getShopName(), "shop_name");
        requireNonBlank(cmd.getPromotionDetail(), "promotion_detail");
        requireNonBlank(cmd.getCategory(), "category");
        if (cmd.getTargetSlots() == null || cmd.getTargetSlots() < 1) {
            throw new DealValidationException("target_slots must be >= 1");
        }
        if (cmd.getPricePerSlot() == null) {
            throw new DealValidationException("price_per_slot is required");
        }
        if (cmd.getExpiresAt() == null) {
            throw new DealValidationException("expires_at is required");
        }

        Deal deal = Deal.builder()
                .creatorId(cmd.getCreatorId())
                .shopName(cmd.getShopName())
                .promotionDetail(cmd.getPromotionDetail())
                .category(cmd.getCategory())
                .menuImageUrl(cmd.getMenuImageUrl())
                .pricePerSlot(cmd.getPricePerSlot())
                .targetSlots(cmd.getTargetSlots())
                .joinedSlots(0)
                .latitude(cmd.getLatitude())
                .longitude(cmd.getLongitude())
                .expiresAt(cmd.getExpiresAt())
                .status(DealStatus.ACTIVE)
                .build();

        return dealRepository.save(deal);
    }

    public Deal getDeal(String dealId) {
        return findOrThrow(dealId);
    }

    public JoinDealResult joinDeal(String dealId, String participantId) {
        requireNonBlank(participantId, "participant_id");
        Deal deal = findOrThrow(dealId);

        if (deal.getStatus() != DealStatus.ACTIVE) {
            throw new DealStateException("Deal is not open for joining, status=" + deal.getStatus());
        }
        if (deal.getJoinedSlots() >= deal.getTargetSlots()) {
            throw new DealStateException("Deal is already full");
        }

        deal.setJoinedSlots(deal.getJoinedSlots() + 1);
        if (deal.getJoinedSlots() >= deal.getTargetSlots()) {
            deal.setStatus(DealStatus.LOCKED);
        }
        deal = dealRepository.save(deal);

        try {
            CreatePaymentResponse payment =
                    paymentServiceClient.createPayment(dealId, participantId, deal.getPricePerSlot());
            LocalDateTime reservedUntil = parseReservedUntil(payment.getReservedUntil());
            return new JoinDealResult(deal, payment.getPaymentId(), reservedUntil);
        } catch (RuntimeException e) {
            releaseSlot(dealId);
            throw e;
        }
    }

    public Deal cancelDeal(String dealId, String requesterId) {
        Deal deal = findOrThrow(dealId);

        if (!deal.getCreatorId().equals(requesterId)) {
            throw new DealPermissionException("Only the deal creator can cancel this deal");
        }
        if (deal.getStatus() != DealStatus.ACTIVE && deal.getStatus() != DealStatus.LOCKED) {
            throw new DealStateException("Deal cannot be cancelled from status " + deal.getStatus());
        }

        deal.setStatus(DealStatus.CANCELLED);
        deal = dealRepository.save(deal);

        try {
            paymentServiceClient.refundDeal(dealId);
        } catch (PaymentServiceException e) {
            log.warn("RefundDeal failed for deal {} after it was already cancelled: {}", dealId, e.getMessage());
        }

        return deal;
    }

    public Deal confirmSlot(String dealId) {
        return findOrThrow(dealId);
    }

    /**
     * DG6 / ReleaseSlot: called by Payment Service when a payment fails or the 5-minute
     * reservation times out. Returns the slot; idempotent (never goes below 0).
     */
    public Deal releaseSlot(String dealId) {
        Deal deal = findOrThrow(dealId);

        if (deal.getJoinedSlots() > 0) {
            deal.setJoinedSlots(deal.getJoinedSlots() - 1);
        }
        if (deal.getStatus() == DealStatus.LOCKED) {
            deal.setStatus(DealStatus.ACTIVE);
        }

        return dealRepository.save(deal);
    }

    private Deal findOrThrow(String dealId) {
        requireNonBlank(dealId, "deal_id");
        return dealRepository.findById(dealId).orElseThrow(() -> new DealNotFoundException(dealId));
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DealValidationException(field + " is required");
        }
    }

    /**
     * Payment Service formats reserved_until with {@code DateTimeFormatter.ISO_LOCAL_DATE_TIME}
     * (e.g. "2026-09-30T12:00:00", no zone/offset) — plain {@link LocalDateTime#parse}, not
     * {@code Instant.parse}, which would throw on a string with no offset.
     */
    private static LocalDateTime parseReservedUntil(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new PaymentServiceException("Payment Service returned an unparseable reserved_until: " + value, e);
        }
    }
}
