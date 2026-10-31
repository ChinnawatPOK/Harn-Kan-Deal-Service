package com.harnkan.deal.domain;

import java.math.BigDecimal;
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
@Table(name = "deals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Deal {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "creator_id", length = 64, nullable = false, updatable = false)
    private String creatorId;

    @Column(name = "creator_name", length = 100, nullable = false)
    private String creatorName;

    @Column(name = "deal_name", length = 255, nullable = false)
    private String dealName;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "category", length = 50, nullable = false)
    private String category;

    @Column(name = "menu_image_url", length = 500)
    private String menuImageUrl;

    @Column(name = "pickup_location", length = 255, nullable = false)
    private String pickupLocation;

    @Column(name = "latitude", precision = 10, scale = 8, nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 11, scale = 8, nullable = false)
    private BigDecimal longitude;

    /** Total the host entered. */
    @Column(name = "total_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal totalPrice;

    /** ceil(total_price / target_slots) in whole baht. */
    @Column(name = "price_per_slot", precision = 10, scale = 2, nullable = false)
    private BigDecimal pricePerSlot;

    /** Total number of people the promotion requires, including the host. */
    @Column(name = "target_slots", nullable = false)
    private Integer targetSlots;

    /** Slots in use: the host plus every RESERVED / PAYMENT_PENDING / HELD / RECEIVED participant. */
    @Column(name = "joined_slots", nullable = false)
    @Builder.Default
    private Integer joinedSlots = 1;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    @Builder.Default
    private DealStatus status = DealStatus.ACTIVE;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = Ids.newId();
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
