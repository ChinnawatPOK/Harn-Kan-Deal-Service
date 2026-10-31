package com.harnkan.deal.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealParticipant;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.domain.ParticipantStatus;
import com.harnkan.deal.repository.DealParticipantRepository;
import com.harnkan.deal.repository.DealRepository;
import com.harnkan.deal.service.DealActions;
import com.harnkan.deal.service.DealService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SCH1-SCH4 (api-design.md 3.3). Each item is handled by its own DealService transaction,
 * so one failing item (e.g. Payment down) never blocks the others and is simply retried next tick.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "deal.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class DealScheduler {

    private final DealService dealService;
    private final DealRepository dealRepository;
    private final DealParticipantRepository participantRepository;
    private final Clock clock;

    /** SCH1: reservations that were not paid in time. */
    @Scheduled(fixedDelayString = "${deal.scheduler.interval-ms:30000}", initialDelayString = "${deal.scheduler.initial-delay-ms:10000}")
    public void expireReservations() {
        List<DealParticipant> late = participantRepository
                .findByStatusAndReservedUntilBefore(ParticipantStatus.RESERVED, Instant.now(clock));
        for (DealParticipant p : late) {
            try {
                if (dealService.expireReservation(p.getDealId(), p.getId())) {
                    log.info("SCH1 released reservation {} on deal {}", p.getId(), p.getDealId());
                }
            } catch (RuntimeException e) {
                log.warn("SCH1 failed for participant {}: {}", p.getId(), e.getMessage());
            }
        }
    }

    /** SCH2: deals past expires_at. */
    @Scheduled(fixedDelayString = "${deal.scheduler.interval-ms:30000}", initialDelayString = "${deal.scheduler.initial-delay-ms:10000}")
    public void expireDeals() {
        for (Deal d : dealRepository.findByStatusInAndExpiresAtBefore(DealActions.OPEN, Instant.now(clock))) {
            try {
                if (dealService.expireDeal(d.getId())) {
                    log.info("SCH2 expired deal {}", d.getId());
                }
            } catch (RuntimeException e) {
                log.warn("SCH2 failed for deal {}: {}", d.getId(), e.getMessage());
            }
        }
    }

    /** SCH4: everyone confirmed receipt but the payout to the host failed. */
    @Scheduled(fixedDelayString = "${deal.scheduler.interval-ms:30000}", initialDelayString = "${deal.scheduler.initial-delay-ms:10000}")
    public void retryPayouts() {
        for (Deal d : dealRepository.findByStatus(DealStatus.LOCKED)) {
            if (!DealActions.allReceived(participantRepository.findByDealIdOrderByJoinedAtAsc(d.getId()))) {
                continue; // cheap check first, so ordinary LOCKED deals are not row-locked every tick
            }
            try {
                if (dealService.retryPayout(d.getId())) {
                    log.info("SCH4 paid out deal {}", d.getId());
                }
            } catch (RuntimeException e) {
                log.warn("SCH4 failed for deal {}: {}", d.getId(), e.getMessage());
            }
        }
    }

    /** SCH3: refunds that did not finish the first time. */
    @Scheduled(fixedDelayString = "${deal.scheduler.interval-ms:30000}", initialDelayString = "${deal.scheduler.initial-delay-ms:10000}")
    public void retryRefunds() {
        Set<String> dealIds = participantRepository.findByStatus(ParticipantStatus.REFUND_PENDING).stream()
                .map(DealParticipant::getDealId)
                .collect(Collectors.toSet());
        for (String dealId : dealIds) {
            try {
                dealService.retryRefund(dealId);
            } catch (RuntimeException e) {
                log.warn("SCH3 failed for deal {}: {}", dealId, e.getMessage());
            }
        }
    }
}
