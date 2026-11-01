package com.harnkan.deal.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.harnkan.deal.util.Ids;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "deal_participants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealParticipant {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "deal_id", length = 32, nullable = false, updatable = false)
    private String dealId;

    /** Logical reference to users._id. */
    @Column(name = "user_id", length = 64, nullable = false, updatable = false)
    private String userId;

    @Column(name = "user_name", length = 100, nullable = false)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private ParticipantRole role;

    /** Null for the host. */
    @Column(name = "payment_id", length = 32)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    private ParticipantStatus status;

    @Column(name = "reserved_until")
    private Instant reservedUntil;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = Ids.newId();
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (this.joinedAt == null) {
            this.joinedAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
