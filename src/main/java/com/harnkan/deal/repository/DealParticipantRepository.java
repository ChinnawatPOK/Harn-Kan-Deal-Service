package com.harnkan.deal.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.harnkan.deal.domain.DealParticipant;
import com.harnkan.deal.domain.ParticipantStatus;

@Repository
public interface DealParticipantRepository extends JpaRepository<DealParticipant, String> {

    List<DealParticipant> findByDealIdOrderByJoinedAtAsc(String dealId);

    List<DealParticipant> findByDealIdIn(Collection<String> dealIds);

    List<DealParticipant> findByUserId(String userId);

    Optional<DealParticipant> findByPaymentId(String paymentId);

    List<DealParticipant> findByStatusAndReservedUntilBefore(ParticipantStatus status, Instant before);

    List<DealParticipant> findByStatus(ParticipantStatus status);
}
