CREATE TABLE IF NOT EXISTS deals (
    id VARCHAR(32) PRIMARY KEY,
    creator_id VARCHAR(32) NOT NULL,
    shop_name VARCHAR(255) NOT NULL,
    promotion_detail TEXT NOT NULL,
    category VARCHAR(50) NOT NULL,
    menu_image_url VARCHAR(500),
    price_per_slot DECIMAL(10,2) NOT NULL,
    target_slots INT NOT NULL,
    joined_slots INT NOT NULL DEFAULT 0,
    latitude DECIMAL(10,8) NOT NULL,
    longitude DECIMAL(11,8) NOT NULL,
    expires_at DATETIME NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
