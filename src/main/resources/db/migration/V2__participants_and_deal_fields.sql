-- api-design.md section 2: deal fields for the final UI + deal_participants.
-- All DATETIME values are UTC.

ALTER TABLE deals
    CHANGE COLUMN shop_name deal_name VARCHAR(255) NOT NULL,
    CHANGE COLUMN promotion_detail details TEXT NULL,
    ADD COLUMN creator_name    VARCHAR(100)  NOT NULL DEFAULT '' AFTER creator_id,
    ADD COLUMN pickup_location VARCHAR(255)  NOT NULL DEFAULT '' AFTER menu_image_url,
    ADD COLUMN total_price     DECIMAL(10,2) NOT NULL DEFAULT 0  AFTER longitude,
    ADD COLUMN purchased_at    DATETIME NULL,
    ADD COLUMN completed_at    DATETIME NULL,
    ADD COLUMN cancelled_at    DATETIME NULL,
    MODIFY COLUMN joined_slots INT NOT NULL DEFAULT 1;

-- rows created by the old demo schema: the host was not counted in joined_slots
UPDATE deals
SET total_price  = price_per_slot * target_slots,
    creator_name = CONCAT('User ', RIGHT(creator_id, 4)),
    joined_slots = joined_slots + 1;

CREATE TABLE IF NOT EXISTS deal_participants (
    id              VARCHAR(32)  PRIMARY KEY,
    deal_id         VARCHAR(32)  NOT NULL,
    user_id         VARCHAR(32)  NOT NULL,
    user_name       VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    payment_id      VARCHAR(32)  NULL,
    status          VARCHAR(50)  NOT NULL,
    reserved_until  DATETIME     NULL,
    received_at     DATETIME     NULL,
    joined_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_participants_deal FOREIGN KEY (deal_id) REFERENCES deals (id),
    INDEX idx_participants_deal (deal_id),
    INDEX idx_participants_user (user_id),
    INDEX idx_participants_payment (payment_id),
    INDEX idx_participants_status_reserved (status, reserved_until)
);

-- the host is always the first participant
INSERT INTO deal_participants (id, deal_id, user_id, user_name, role, status, joined_at, updated_at)
SELECT REPLACE(UUID(), '-', ''), id, creator_id, creator_name, 'HOST', 'HOST', created_at, created_at
FROM deals;
