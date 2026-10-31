package com.harnkan.deal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.harnkan.deal.dto.ReceiptDto;
import com.harnkan.deal.dto.ReceiptLineDto;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealParticipant;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.domain.ParticipantRole;
import com.harnkan.deal.domain.ParticipantStatus;
import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.repository.DealParticipantRepository;
import com.harnkan.deal.repository.DealRepository;
import com.harnkan.deal.util.Geo;
import com.harnkan.deal.dto.DealDetailDto;
import com.harnkan.deal.dto.JoinOnlyActionsDto;
import com.harnkan.deal.dto.MeDto;
import com.harnkan.deal.dto.MineItemDto;
import com.harnkan.deal.dto.NearbyItemDto;
import com.harnkan.deal.dto.PageDto;
import com.harnkan.deal.dto.ParticipantDto;

import lombok.RequiredArgsConstructor;

/** Read models: DR3 nearby, DR4 detail, DR5 mine. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DealQueryService {

    private static final double METERS_PER_DEGREE = 111_320d;
    private static final int MAX_RADIUS_METERS = 50_000;
    private static final int MAX_PAGE_SIZE = 100;

    private static final Set<DealStatus> IN_PROGRESS = EnumSet.of(DealStatus.ACTIVE, DealStatus.LOCKED);
    private static final Set<DealStatus> FINISHED =
            EnumSet.of(DealStatus.CANCELLED, DealStatus.EXPIRED, DealStatus.COMPLETED);
    private static final Set<ParticipantStatus> JOINED_TAB = EnumSet.of(
            ParticipantStatus.RESERVED, ParticipantStatus.PAYMENT_PENDING, ParticipantStatus.HELD, ParticipantStatus.RECEIVED);

    private final DealRepository dealRepository;
    private final DealParticipantRepository participantRepository;
    private final Clock clock;

    // --- DR4 ------------------------------------------------------------------------

    public DealDetailDto detail(String dealId, String userId, Double lat, Double lng) {
        Deal deal = dealRepository.findById(dealId).orElseThrow(() -> new DealNotFoundException(dealId));
        List<DealParticipant> rows = sorted(participantRepository.findByDealIdOrderByJoinedAtAsc(dealId));
        Instant now = Instant.now(clock);

        boolean showAll = deal.getStatus() == DealStatus.CANCELLED || deal.getStatus() == DealStatus.EXPIRED;
        List<DealParticipant> visible = rows.stream()
                .filter(p -> showAll ? p.getStatus() != ParticipantStatus.FAILED : p.getStatus().isCounted())
                .toList();
        List<ParticipantDto> participants = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            DealParticipant p = visible.get(i);
            participants.add(new ParticipantDto(i + 1, p.getUserId(), p.getUserName(),
                    p.getRole().name(), p.getStatus().name(), p.getReservedUntil()));
        }

        DealParticipant me = userId == null ? null : DealActions.currentRow(
                rows.stream().filter(p -> p.getUserId().equals(userId)).toList());

        Integer distance = lat == null || lng == null ? null
                : (int) Math.round(Geo.distanceMeters(lat, lng,
                        deal.getLatitude().doubleValue(), deal.getLongitude().doubleValue()));

        return new DealDetailDto(
                deal.getId(), deal.getDealName(), deal.getDetails(), deal.getCategory(), deal.getMenuImageUrl(),
                deal.getPickupLocation(), deal.getLatitude(), deal.getLongitude(),
                money(deal.getTotalPrice()), money(deal.getPricePerSlot()),
                deal.getTargetSlots(), deal.getJoinedSlots(), deal.getStatus().name(),
                deal.getExpiresAt(), deal.getCompletedAt(), deal.getCancelledAt(),
                deal.getCreatedAt(), distance, participants,
                me == null ? null : toMe(deal, me, rows, now),
                new JoinOnlyActionsDto(DealActions.canJoin(deal, null, now)));
    }

    private static MeDto toMe(Deal deal, DealParticipant me, List<DealParticipant> rows, Instant now) {
        boolean host = me.getRole() == ParticipantRole.HOST;
        boolean refunding = me.getStatus() == ParticipantStatus.REFUND_PENDING
                || me.getStatus() == ParticipantStatus.REFUNDED;
        return new MeDto(
                me.getRole().name(), me.getStatus().name(), me.getPaymentId(),
                host ? money(BigDecimal.ZERO) : money(deal.getPricePerSlot()),
                refunding ? money(deal.getPricePerSlot()) : money(BigDecimal.ZERO),
                me.getReservedUntil(),
                DealActions.forCaller(deal, me, rows, now));
    }

    // --- DR11 -----------------------------------------------------------------------

    /** Only for a COMPLETED deal, and only for its host or a participant who paid and confirmed receipt. */
    public ReceiptDto receipt(String dealId, String userId) {
        Deal deal = dealRepository.findById(dealId).orElseThrow(() -> new DealNotFoundException(dealId));
        if (deal.getStatus() != DealStatus.COMPLETED) {
            throw new DealStateException("Receipt is available once the deal is completed, status=" + deal.getStatus());
        }
        List<DealParticipant> rows = sorted(participantRepository.findByDealIdOrderByJoinedAtAsc(dealId));
        DealParticipant me = DealActions.currentRow(rows.stream().filter(p -> p.getUserId().equals(userId)).toList());
        boolean host = me != null && me.getRole() == ParticipantRole.HOST;
        if (me == null || !(host || me.getStatus() == ParticipantStatus.RECEIVED)) {
            throw new DealPermissionException("Only the host or a participant who paid can see this receipt");
        }

        BigDecimal share = money(deal.getPricePerSlot());
        List<ReceiptLineDto> lines = rows.stream()
                .filter(p -> p.getRole() == ParticipantRole.PARTICIPANT && p.getStatus() == ParticipantStatus.RECEIVED)
                .map(p -> new ReceiptLineDto(p.getUserId().equals(userId) ? "You" : p.getUserName(),
                        p.getRole().name(), share))
                .toList();
        BigDecimal payout = money(share.multiply(BigDecimal.valueOf(lines.size())));
        String receiptNo = "RC-" + deal.getId().substring(0, Math.min(8, deal.getId().length())).toUpperCase(Locale.ROOT)
                + "-" + userId.substring(Math.max(0, userId.length() - 4)).toUpperCase(Locale.ROOT);

        return new ReceiptDto(receiptNo, deal.getId(), deal.getDealName(), deal.getCategory(), deal.getCompletedAt(),
                money(deal.getTotalPrice()), share, me.getRole().name(), host ? payout : share,
                host ? null : me.getPaymentId(), payout, lines);
    }

    // --- DR3 ------------------------------------------------------------------------

    public PageDto<NearbyItemDto> nearby(double lat, double lng, int radius, String category, String q,
            int page, int size) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new DealValidationException("lat/lng out of range");
        }
        int radiusMeters = Math.min(Math.max(radius, 1), MAX_RADIUS_METERS);
        int pageSize = pageSize(size);
        int pageIndex = Math.max(page, 0);

        double dLat = radiusMeters / METERS_PER_DEGREE;
        double dLng = radiusMeters / (METERS_PER_DEGREE * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        String wantedCategory = category == null || category.isBlank() ? null : category.trim();
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);

        Map<Deal, Integer> distances = new HashMap<>();
        List<Deal> matches = dealRepository.findOpenInBox(DealStatus.ACTIVE, Instant.now(clock),
                        BigDecimal.valueOf(lat - dLat), BigDecimal.valueOf(lat + dLat),
                        BigDecimal.valueOf(lng - dLng), BigDecimal.valueOf(lng + dLng)).stream()
                .filter(d -> wantedCategory == null || d.getCategory().equalsIgnoreCase(wantedCategory))
                .filter(d -> needle == null || d.getDealName().toLowerCase(Locale.ROOT).contains(needle))
                .filter(d -> {
                    long meters = Math.round(Geo.distanceMeters(lat, lng,
                            d.getLatitude().doubleValue(), d.getLongitude().doubleValue()));
                    distances.put(d, (int) meters);
                    return meters <= radiusMeters;
                })
                .sorted(Comparator.comparing(distances::get))
                .toList();

        List<Deal> pageOfDeals = slice(matches, pageIndex, pageSize);
        Map<String, List<DealParticipant>> rowsByDeal = rowsByDeal(
                pageOfDeals.stream().map(Deal::getId).toList());

        List<NearbyItemDto> items = pageOfDeals.stream()
                .map(d -> new NearbyItemDto(d.getId(), d.getDealName(), d.getDetails(), d.getCategory(),
                        money(d.getPricePerSlot()), d.getJoinedSlots(), d.getTargetSlots(), d.getExpiresAt(),
                        distances.get(d), initials(rowsByDeal.getOrDefault(d.getId(), List.of()))))
                .toList();
        return new PageDto<>(items, pageIndex, pageSize, matches.size());
    }

    // --- DR5 ------------------------------------------------------------------------

    public PageDto<MineItemDto> mine(String userId, String tab, String category, String role, int page, int size) {
        if (tab == null || !Set.of("created", "joined", "history").contains(tab)) {
            throw new DealValidationException("tab must be created, joined or history");
        }
        ParticipantRole wantedRole = parseRole(role);
        String wantedCategory = category == null || category.isBlank() ? null : category.trim();
        int pageSize = pageSize(size);
        int pageIndex = Math.max(page, 0);

        // one row per deal: the live one, or the latest if the user left
        Map<String, List<DealParticipant>> byDeal = participantRepository.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(DealParticipant::getDealId));
        Map<String, DealParticipant> myRow = new LinkedHashMap<>();
        byDeal.forEach((dealId, userRows) -> myRow.put(dealId, DealActions.currentRow(userRows)));

        Map<String, Deal> deals = dealRepository.findAllById(myRow.keySet()).stream()
                .collect(Collectors.toMap(Deal::getId, d -> d));

        List<Deal> matches = myRow.entrySet().stream()
                .filter(e -> deals.containsKey(e.getKey()))
                .filter(e -> inTab(tab, deals.get(e.getKey()), e.getValue()))
                .filter(e -> wantedRole == null || e.getValue().getRole() == wantedRole)
                .map(e -> deals.get(e.getKey()))
                .filter(d -> wantedCategory == null || d.getCategory().equalsIgnoreCase(wantedCategory))
                .sorted(Comparator.comparing(Deal::getCreatedAt).reversed())
                .toList();

        List<Deal> pageOfDeals = slice(matches, pageIndex, pageSize);
        Map<String, List<DealParticipant>> rowsByDeal = rowsByDeal(
                pageOfDeals.stream().map(Deal::getId).toList());

        List<MineItemDto> items = pageOfDeals.stream().map(d -> {
            DealParticipant mine = myRow.get(d.getId());
            List<DealParticipant> all = rowsByDeal.getOrDefault(d.getId(), List.of());
            boolean host = mine.getRole() == ParticipantRole.HOST;
            int ready = (int) all.stream().filter(p -> p.getRole() == ParticipantRole.HOST
                    || p.getStatus() == ParticipantStatus.HELD || p.getStatus() == ParticipantStatus.RECEIVED).count();
            boolean readyToOrder = host && DealActions.fullyPaid(d, all);
            return new MineItemDto(d.getId(), d.getDealName(), d.getCategory(), d.getStatus().name(),
                    mine.getRole().name(), mine.getStatus().name(), money(d.getTotalPrice()), money(d.getPricePerSlot()),
                    d.getJoinedSlots(), d.getTargetSlots(), ready, readyToOrder, d.getExpiresAt(), d.getCreatedAt());
        }).toList();
        return new PageDto<>(items, pageIndex, pageSize, matches.size());
    }

    private static boolean inTab(String tab, Deal deal, DealParticipant mine) {
        return switch (tab) {
            case "created" -> mine.getRole() == ParticipantRole.HOST && IN_PROGRESS.contains(deal.getStatus());
            case "joined" -> mine.getRole() == ParticipantRole.PARTICIPANT
                    && JOINED_TAB.contains(mine.getStatus()) && IN_PROGRESS.contains(deal.getStatus());
            default -> FINISHED.contains(deal.getStatus()) || mine.getStatus() == ParticipantStatus.FAILED;
        };
    }

    // --- helpers --------------------------------------------------------------------

    private Map<String, List<DealParticipant>> rowsByDeal(Collection<String> dealIds) {
        if (dealIds.isEmpty()) {
            return Map.of();
        }
        return participantRepository.findByDealIdIn(dealIds).stream()
                .collect(Collectors.groupingBy(DealParticipant::getDealId));
    }

    /** "Ploy S." -> "PS": first letter of up to two words, for the avatar stack on a card. */
    private static List<String> initials(List<DealParticipant> rows) {
        return sorted(rows).stream()
                .filter(p -> p.getStatus().isCounted())
                .map(p -> {
                    StringBuilder sb = new StringBuilder();
                    for (String word : p.getUserName().trim().split("\\s+")) {
                        if (!word.isEmpty() && sb.length() < 2) {
                            sb.append(Character.toUpperCase(word.charAt(0)));
                        }
                    }
                    return sb.toString();
                })
                .toList();
    }

    /** Host first, then by join time. */
    private static List<DealParticipant> sorted(List<DealParticipant> rows) {
        return rows.stream()
                .sorted(Comparator.comparing((DealParticipant p) -> p.getRole() != ParticipantRole.HOST)
                        .thenComparing(DealParticipant::getJoinedAt)
                        .thenComparing(DealParticipant::getId))
                .toList();
    }

    private static <T> List<T> slice(List<T> all, int page, int size) {
        long from = (long) page * size;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList((int) from, (int) Math.min(all.size(), from + size));
    }

    private static int pageSize(int size) {
        return size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }

    private static ParticipantRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return ParticipantRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DealValidationException("role must be HOST or PARTICIPANT");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
