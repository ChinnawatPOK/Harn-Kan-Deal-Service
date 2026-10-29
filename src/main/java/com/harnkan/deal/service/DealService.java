package com.harnkan.deal.service;

import static com.harnkan.deal.service.DealActions.OPEN;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.amqp.AmqpException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.harnkan.deal.grpc.payment.CreatePaymentResponse;
import com.harnkan.deal.grpc.payment.PaymentStatusItem;
import com.harnkan.deal.grpc.payment.RefundDealResponse;
import com.harnkan.deal.messaging.DealEventPublisher;
import com.harnkan.deal.repository.DealParticipantRepository;
import com.harnkan.deal.repository.DealRepository;
import com.harnkan.deal.web.CurrentUser;

import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Commands that change a deal. Each one runs in a single transaction that starts by locking the
 * deal row (SELECT ... FOR UPDATE), so join / leave / cancel / payment callbacks / schedulers
 * never interleave on the same deal. Read models live in {@link DealQueryService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DealService {

    private static final Set<String> CATEGORIES = Set.of("FOOD", "DRINK");
    private static final int MIN_TARGET_SLOTS = 2;
    private static final int MAX_TARGET_SLOTS = 20;

    /** Rows whose money (or reservation) Payment has to settle when a deal dies. */
    private static final Set<ParticipantStatus> TO_SETTLE = EnumSet.of(
            ParticipantStatus.RESERVED, ParticipantStatus.PAYMENT_PENDING,
            ParticipantStatus.HELD, ParticipantStatus.REFUND_PENDING);
    private static final Set<ParticipantStatus> RETRY_ONLY = EnumSet.of(ParticipantStatus.REFUND_PENDING);

    private final DealRepository dealRepository;
    private final DealParticipantRepository participantRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final PaymentTimeParser paymentTimeParser;
    private final DealEventPublisher dealEventPublisher;
    private final Clock clock;

    // --- DR2 / DG1 ------------------------------------------------------------------

    @Transactional
    public Deal createDeal(CreateDealCommand cmd) {
        requireNonBlank(cmd.getCreatorId(), "creator_id");
        requireNonBlank(cmd.getCreatorName(), "creator_name");
        requireNonBlank(cmd.getDealName(), "dealName");
        requireNonBlank(cmd.getPickupLocation(), "pickupLocation");
        requireNonBlank(cmd.getCategory(), "category");
        String category = cmd.getCategory().trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new DealValidationException("category must be FOOD or DRINK");
        }
        if (cmd.getLatitude() == null || cmd.getLongitude() == null
                || cmd.getLatitude().abs().compareTo(BigDecimal.valueOf(90)) > 0
                || cmd.getLongitude().abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new DealValidationException("latitude/longitude are missing or out of range");
        }
        if (cmd.getTotalPrice() == null || cmd.getTotalPrice().signum() <= 0) {
            throw new DealValidationException("totalPrice must be greater than 0");
        }
        if (cmd.getTargetSlots() == null
                || cmd.getTargetSlots() < MIN_TARGET_SLOTS || cmd.getTargetSlots() > MAX_TARGET_SLOTS) {
            throw new DealValidationException(
                    "targetSlots must be between " + MIN_TARGET_SLOTS + " and " + MAX_TARGET_SLOTS);
        }
        Instant now = now();
        if (cmd.getExpiresAt() == null || !cmd.getExpiresAt().isAfter(now)) {
            throw new DealValidationException("expires_at must be in the future");
        }

        Deal deal = dealRepository.save(Deal.builder()
                .creatorId(cmd.getCreatorId())
                .creatorName(cmd.getCreatorName())
                .dealName(cmd.getDealName().trim())
                .details(cmd.getDetails())
                .category(category)
                .menuImageUrl(cmd.getMenuImageUrl())
                .pickupLocation(cmd.getPickupLocation().trim())
                .latitude(cmd.getLatitude())
                .longitude(cmd.getLongitude())
                .totalPrice(money(cmd.getTotalPrice()))
                .pricePerSlot(pricePerSlot(cmd.getTotalPrice(), cmd.getTargetSlots()))
                .targetSlots(cmd.getTargetSlots())
                .joinedSlots(1)
                .expiresAt(cmd.getExpiresAt())
                .status(DealStatus.ACTIVE)
                .build());

        participantRepository.save(DealParticipant.builder()
                .dealId(deal.getId())
                .userId(cmd.getCreatorId())
                .userName(cmd.getCreatorName())
                .role(ParticipantRole.HOST)
                .status(ParticipantStatus.HOST)
                .joinedAt(now)
                .build());

        // Best-effort: the deal is already saved, so a broker outage must not fail the request.
        try {
            dealEventPublisher.publishDealCreated(deal);
        } catch (AmqpException e) {
            log.warn("Publishing deal.created failed for deal {}: {}", deal.getId(), e.getMessage());
        }
        return deal;
    }

    /** ceil(total / target) in whole baht, e.g. 756/4 = 189, 760/3 = 254. */
    public static BigDecimal pricePerSlot(BigDecimal totalPrice, int targetSlots) {
        return totalPrice.divide(BigDecimal.valueOf(targetSlots), 0, RoundingMode.CEILING).setScale(2);
    }

    @Transactional(readOnly = true)
    public Deal getDeal(String dealId) {
        requireNonBlank(dealId, "deal_id");
        return dealRepository.findById(dealId).orElseThrow(() -> new DealNotFoundException(dealId));
    }

    // --- DR7 ------------------------------------------------------------------------

    @Transactional
    public JoinDealResult joinDeal(String dealId, CurrentUser user) {
        Deal deal = lock(dealId);
        Instant now = now();
        List<DealParticipant> mine = participantRepository.findByDealIdOrderByJoinedAtAsc(dealId).stream()
                .filter(p -> p.getUserId().equals(user.id()))
                .toList();

        if (deal.getCreatorId().equals(user.id())) {
            throw new DealStateException("The host cannot join their own deal");
        }
        if (mine.stream().anyMatch(p -> p.getStatus().isCounted())) {
            throw new DealStateException("ALREADY_JOINED", "You already joined this deal");
        }
        if (deal.getStatus() == DealStatus.LOCKED) {
            throw new DealStateException("DEAL_FULL", "Deal is already full");
        }
        if (deal.getStatus() != DealStatus.ACTIVE) {
            throw new DealStateException("Deal is not open for joining, status=" + deal.getStatus());
        }
        if (!deal.getExpiresAt().isAfter(now)) {
            throw new DealStateException("DEAL_EXPIRED", "Deal has expired");
        }
        if (deal.getJoinedSlots() >= deal.getTargetSlots()) {
            throw new DealStateException("DEAL_FULL", "Deal is already full");
        }

        // A failure here rolls everything back: nothing was written yet.
        CreatePaymentResponse payment =
                paymentServiceClient.createPayment(dealId, user.id(), deal.getPricePerSlot());

        DealParticipant participant = participantRepository.save(DealParticipant.builder()
                .dealId(dealId)
                .userId(user.id())
                .userName(user.name())
                .role(ParticipantRole.PARTICIPANT)
                .paymentId(payment.getPaymentId())
                .status(ParticipantStatus.RESERVED)
                .reservedUntil(paymentTimeParser.parse(payment.getReservedUntil()))
                .joinedAt(now)
                .build());

        deal.setJoinedSlots(deal.getJoinedSlots() + 1);
        if (deal.getJoinedSlots() >= deal.getTargetSlots()) {
            deal.setStatus(DealStatus.LOCKED);
        }
        return new JoinDealResult(dealRepository.save(deal), participant);
    }

    // --- DR10 -----------------------------------------------------------------------

    @Transactional
    public Deal leaveDeal(String dealId, CurrentUser user) {
        Deal deal = lock(dealId);
        List<DealParticipant> rows = participantRepository.findByDealIdOrderByJoinedAtAsc(dealId).stream()
                .filter(p -> p.getUserId().equals(user.id()))
                .toList();
        DealParticipant me = DealActions.currentRow(rows);

        if (me == null || !me.getStatus().isCounted()) {
            throw new DealStateException("You have not joined this deal");
        }
        if (me.getRole() == ParticipantRole.HOST) {
            throw new DealStateException("The host cannot leave; cancel the deal instead");
        }
        if (me.getStatus() != ParticipantStatus.RESERVED && me.getStatus() != ParticipantStatus.PAYMENT_PENDING) {
            throw new DealStateException("You cannot leave after paying, status=" + me.getStatus());
        }
        if (!OPEN.contains(deal.getStatus())) {
            throw new DealStateException("Deal is no longer open, status=" + deal.getStatus());
        }

        cancelPaymentOrSkipIfUnsupported(me.getPaymentId());
        failParticipant(deal, me);
        return deal;
    }

    // --- DR6 / DG4 ------------------------------------------------------------------

    @Transactional
    public Deal cancelDeal(String dealId, String requesterId) {
        Deal deal = lock(dealId);

        if (!deal.getCreatorId().equals(requesterId)) {
            throw new DealPermissionException("Only the deal creator can cancel this deal");
        }
        if (!OPEN.contains(deal.getStatus())) {
            throw new DealStateException("Deal cannot be cancelled from status " + deal.getStatus());
        }

        deal.setStatus(DealStatus.CANCELLED);
        deal.setCancelledAt(now());
        deal = dealRepository.save(deal);

        refundAndApply(deal, TO_SETTLE);
        return deal;
    }

    // --- DG5 ------------------------------------------------------------------------

    /**
     * Payment tells us a payment changed. Idempotent: the same status twice changes nothing,
     * and a finished row (FAILED, REFUND_PENDING, REFUNDED, RECEIVED) is never moved back.
     */
    @Transactional
    public Deal updateParticipantPayment(String dealId, String participantUserId, String paymentId, String status) {
        ParticipantStatus target = parsePaymentStatus(status);
        Deal deal = lock(dealId);
        DealParticipant p = findParticipant(dealId, participantUserId, paymentId);
        ParticipantStatus current = p.getStatus();

        if (current == target) {
            return deal;
        }
        boolean waiting = current == ParticipantStatus.RESERVED || current == ParticipantStatus.PAYMENT_PENDING;
        boolean dealDead = deal.getStatus() == DealStatus.CANCELLED || deal.getStatus() == DealStatus.EXPIRED;

        if (target == ParticipantStatus.HELD && waiting && dealDead) {
            // money arrived after the deal was cancelled: SCH3 will refund it
            p.setStatus(ParticipantStatus.REFUND_PENDING);
            participantRepository.save(p);
        } else if (target == ParticipantStatus.HELD && waiting) {
            p.setStatus(ParticipantStatus.HELD);
            p.setReservedUntil(null);
            participantRepository.save(p);
        } else if (target == ParticipantStatus.PAYMENT_PENDING && current == ParticipantStatus.RESERVED) {
            p.setStatus(ParticipantStatus.PAYMENT_PENDING);
            participantRepository.save(p);
        } else if (target == ParticipantStatus.FAILED && waiting) {
            failParticipant(deal, p);
        } else {
            log.info("Ignoring payment update {} for participant {} already in status {}", target, p.getId(), current);
        }
        return deal;
    }

    // --- schedulers (api-design.md 3.3) ----------------------------------------------

    /** SCH1: a RESERVED participant did not pay in time. Returns true when the slot was released. */
    @Transactional
    public boolean expireReservation(String dealId, String participantRowId) {
        Deal deal = lock(dealId);
        DealParticipant p = participantRepository.findById(participantRowId).orElse(null);
        if (p == null || p.getStatus() != ParticipantStatus.RESERVED
                || p.getReservedUntil() == null || p.getReservedUntil().isAfter(now())) {
            return false;
        }
        try {
            cancelPaymentOrSkipIfUnsupported(p.getPaymentId());
        } catch (PaymentServiceException e) {
            if (e.is(Status.Code.FAILED_PRECONDITION)) {
                log.info("Reservation {} not expired: Payment says the payment already moved on", p.getId());
                return false;
            }
            throw e;
        }
        failParticipant(deal, p);
        return true;
    }

    /** SCH2: the deal ran past expires_at. */
    @Transactional
    public boolean expireDeal(String dealId) {
        Deal deal = lock(dealId);
        if (!OPEN.contains(deal.getStatus()) || deal.getExpiresAt().isAfter(now())) {
            return false;
        }
        deal.setStatus(DealStatus.EXPIRED);
        dealRepository.save(deal);
        refundAndApply(deal, TO_SETTLE);
        return true;
    }

    /** SCH3: ask Payment again about participants still waiting for their refund. */
    @Transactional
    public void retryRefund(String dealId) {
        refundAndApply(lock(dealId), RETRY_ONLY);
    }

    // --- internals --------------------------------------------------------------------

    private Deal lock(String dealId) {
        requireNonBlank(dealId, "deal_id");
        return dealRepository.findByIdForUpdate(dealId).orElseThrow(() -> new DealNotFoundException(dealId));
    }

    /** A participant stops holding a slot: FAILED, joined_slots - 1, LOCKED goes back to ACTIVE. */
    private void failParticipant(Deal deal, DealParticipant p) {
        boolean heldSlot = p.getStatus().isCounted();
        p.setStatus(ParticipantStatus.FAILED);
        p.setReservedUntil(null);
        participantRepository.save(p);

        if (heldSlot && OPEN.contains(deal.getStatus())) {
            deal.setJoinedSlots(Math.max(1, deal.getJoinedSlots() - 1));
            if (deal.getStatus() == DealStatus.LOCKED) {
                deal.setStatus(DealStatus.ACTIVE);
            }
            dealRepository.save(deal);
        }
    }

    /** Payments that predate CancelPayment on Payment's side answer UNIMPLEMENTED; carry on without them. */
    private void cancelPaymentOrSkipIfUnsupported(String paymentId) {
        if (paymentId == null) {
            return;
        }
        try {
            paymentServiceClient.cancelPayment(paymentId);
        } catch (PaymentServiceException e) {
            if (!e.is(Status.Code.UNIMPLEMENTED)) {
                throw e;
            }
            log.warn("Payment Service has no CancelPayment yet; releasing the slot without it ({})", paymentId);
        }
    }

    /**
     * Asks Payment to refund the deal, then records the outcome on each participant in {@code scope}.
     * Best-effort: when Payment is unreachable the deal stays cancelled, unpaid rows fail and paid
     * rows wait as REFUND_PENDING for SCH3.
     */
    private void refundAndApply(Deal deal, Set<ParticipantStatus> scope) {
        List<DealParticipant> rows = participantRepository.findByDealIdOrderByJoinedAtAsc(deal.getId()).stream()
                .filter(p -> p.getRole() == ParticipantRole.PARTICIPANT && scope.contains(p.getStatus()))
                .toList();

        RefundDealResponse response;
        try {
            response = paymentServiceClient.refundDeal(deal.getId());
        } catch (PaymentServiceException e) {
            log.warn("RefundDeal failed for deal {} (will retry): {}", deal.getId(), e.getMessage());
            for (DealParticipant p : rows) {
                p.setStatus(p.getStatus() == ParticipantStatus.RESERVED
                        ? ParticipantStatus.FAILED : ParticipantStatus.REFUND_PENDING);
            }
            participantRepository.saveAll(rows);
            return;
        }

        // A user who left and rejoined has two payments in the deal; payment_id tells them apart,
        // participant_id is only the fallback for a row without a payment of its own in the answer.
        Map<String, String> reportedByPayment = new HashMap<>();
        Map<String, String> reportedByUser = new HashMap<>();
        for (PaymentStatusItem item : response.getPaymentsList()) {
            reportedByPayment.put(item.getPaymentId(), item.getStatus());
            reportedByUser.put(item.getParticipantId(), item.getStatus());
        }
        java.util.function.Function<DealParticipant, String> reported = p ->
                p.getPaymentId() != null && reportedByPayment.containsKey(p.getPaymentId())
                        ? reportedByPayment.get(p.getPaymentId())
                        : reportedByUser.get(p.getUserId());
        // Payment without payments[] (older build): only refunded_count tells us how many were refunded.
        long unreported = rows.stream()
                .filter(p -> reported.apply(p) == null && ParticipantStatus.MONEY_AT_PAYMENT.contains(p.getStatus()))
                .count();
        boolean allRefunded = response.getRefundedCount() >= unreported;

        List<DealParticipant> changed = new ArrayList<>();
        for (DealParticipant p : rows) {
            ParticipantStatus next = fromRefundStatus(reported.apply(p));
            if (next == null) {
                if (p.getStatus() == ParticipantStatus.RESERVED) {
                    next = ParticipantStatus.FAILED;
                } else {
                    next = allRefunded ? ParticipantStatus.REFUNDED : ParticipantStatus.REFUND_PENDING;
                }
            }
            if (next != p.getStatus()) {
                p.setStatus(next);
                p.setReservedUntil(null);
                changed.add(p);
            }
        }
        participantRepository.saveAll(changed);
    }

    private static ParticipantStatus fromRefundStatus(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "FAILED" -> ParticipantStatus.FAILED;
            case "REFUND_PENDING" -> ParticipantStatus.REFUND_PENDING;
            case "REFUNDED" -> ParticipantStatus.REFUNDED;
            default -> null;
        };
    }

    private DealParticipant findParticipant(String dealId, String userId, String paymentId) {
        if (paymentId != null && !paymentId.isBlank()) {
            return participantRepository.findByPaymentId(paymentId)
                    .filter(p -> p.getDealId().equals(dealId))
                    .orElseThrow(() -> new ParticipantNotFoundException("No participant for payment " + paymentId));
        }
        requireNonBlank(userId, "participant_id");
        DealParticipant p = DealActions.currentRow(
                participantRepository.findByDealIdOrderByJoinedAtAsc(dealId).stream()
                        .filter(row -> row.getUserId().equals(userId))
                        .toList());
        if (p == null) {
            throw new ParticipantNotFoundException("User " + userId + " is not in deal " + dealId);
        }
        return p;
    }

    private static ParticipantStatus parsePaymentStatus(String status) {
        if (status != null) {
            switch (status) {
                case "PAYMENT_PENDING":
                    return ParticipantStatus.PAYMENT_PENDING;
                case "HELD":
                    return ParticipantStatus.HELD;
                case "FAILED":
                    return ParticipantStatus.FAILED;
                default:
                    break;
            }
        }
        throw new DealValidationException("status must be PAYMENT_PENDING, HELD or FAILED, got: " + status);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DealValidationException(field + " is required");
        }
    }
}
