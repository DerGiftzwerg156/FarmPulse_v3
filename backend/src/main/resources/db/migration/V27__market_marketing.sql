-- Roadmap V3 R3-M: price alarms, forward contracts, farm shop.
-- R3-M1: alarm on a price of a fill type at a sell point (null = best price of all sell points).
CREATE TABLE price_alarm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    fill_type VARCHAR(64) NOT NULL,
    sell_point VARCHAR(128),
    threshold DOUBLE NOT NULL,
    direction VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_game_time BIGINT NOT NULL,
    fired_game_time BIGINT,
    fired_price DOUBLE,
    fired_sell_point VARCHAR(128)
);
-- R3-M2: harvest sold in advance at a fixed price (PRICE_EVENT / FIXED in the delivery month).
CREATE TABLE forward_contract (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    fill_type VARCHAR(64) NOT NULL,
    sell_point VARCHAR(128) NOT NULL,
    quantity BIGINT NOT NULL,
    fixed_price BIGINT NOT NULL,
    base_price DOUBLE NOT NULL,
    lead_months INT NOT NULL,
    delivery_start_game_time BIGINT NOT NULL,
    deadline_game_time BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    instruction_id VARCHAR(64),
    created_game_time BIGINT NOT NULL,
    delivered_quantity BIGINT,
    end_reason VARCHAR(32),
    penalty BIGINT
);
-- R3-M3: order probability factor of the farm shop (refusals lower it, deliveries raise it again).
ALTER TABLE savegame ADD COLUMN farm_shop_factor DOUBLE DEFAULT 1.0 NOT NULL;
