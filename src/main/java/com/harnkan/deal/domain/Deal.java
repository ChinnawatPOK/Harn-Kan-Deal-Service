package com.harnkan.deal.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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

    /** Logical reference to users._id (User Service) — no FK in the DB. */
    @Column(name = "creator_id", length = 32, nullable = false, updatable = false)
    private String creatorId;

    @Column(name = "shop_name", length = 255, nullable = false)
    private String shopName;

    @Column(name = "promotion_detail", columnDefinition = "TEXT", nullable = false)
    private String promotionDetail;

    @Column(name = "category", length = 50, nullable = false)
    private String category;

    @Column(name = "menu_image_url", length = 500)
    private String menuImageUrl;

    @Column(name = "price_per_slot", precision = 10, scale = 2, nullable = false)
    private BigDecimal pricePerSlot;

    /** Total number of people the promotion requires, including the creator. */
    @Column(name = "target_slots", nullable = false)
    private Integer targetSlots;

    /** Slots currently in use, counting RESERVED and successfully paid participants. */
    @Column(name = "joined_slots", nullable = false)
    @Builder.Default
    private Integer joinedSlots = 0;

    @Column(name = "latitude", precision = 10, scale = 8, nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 11, scale = 8, nullable = false)
    private BigDecimal longitude;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    @Builder.Default
    private DealStatus status = DealStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString().replace("-", "");
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
