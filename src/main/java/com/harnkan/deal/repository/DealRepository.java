package com.harnkan.deal.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealStatus;

import jakarta.persistence.LockModeType;

@Repository
public interface DealRepository extends JpaRepository<Deal, String> {

    /** SELECT ... FOR UPDATE: every command that changes a deal starts here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Deal d where d.id = :id")
    Optional<Deal> findByIdForUpdate(@Param("id") String id);

    @Query("""
            select d from Deal d
            where d.status = :status and d.expiresAt > :now
              and d.latitude between :minLat and :maxLat
              and d.longitude between :minLng and :maxLng
            """)
    List<Deal> findOpenInBox(@Param("status") DealStatus status, @Param("now") Instant now,
            @Param("minLat") BigDecimal minLat, @Param("maxLat") BigDecimal maxLat,
            @Param("minLng") BigDecimal minLng, @Param("maxLng") BigDecimal maxLng);

    List<Deal> findByStatusInAndExpiresAtBefore(Collection<DealStatus> statuses, Instant now);
}
