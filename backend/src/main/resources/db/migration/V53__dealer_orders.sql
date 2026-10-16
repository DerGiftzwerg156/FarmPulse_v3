-- Roadmap vehicle dealer RFH-B / L / V (owner decisions 2026-10-15): requests, offers and orders of new machines.
-- dealer_order: one request of the cart; status REQUESTED -> OFFERED (negotiation) -> DEPOSIT_PENDING -> ORDERED ->
-- COMPLETED, or REJECTED (no agreement), EXPIRED, FAILED (deposit refused), CANCELLED. Prices in whole euros:
-- list_total = Σ list prices, trade_in_offer = the dealer's offer for the trade-ins (90-100 % of their game value by
-- trust), offer_price = list_total - trade_in_offer, floor_price = hidden lowest acceptable net price,
-- agreed_price = negotiated net price, deposit = 30 % of it.
CREATE TABLE dealer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    status VARCHAR(20) NOT NULL,
    together BOOLEAN NOT NULL,
    requested_game_time BIGINT NOT NULL,
    offer_game_time BIGINT,
    valid_until_game_time BIGINT,
    list_total BIGINT DEFAULT 0 NOT NULL,
    trade_in_value BIGINT DEFAULT 0 NOT NULL,
    trade_in_offer BIGINT DEFAULT 0 NOT NULL,
    offer_price BIGINT,
    floor_price BIGINT,
    agreed_price BIGINT,
    deposit BIGINT,
    negotiation_id BIGINT,
    closed_game_time BIGINT,
    returned_to_cart BOOLEAN DEFAULT FALSE NOT NULL
);
CREATE INDEX ix_dealer_order_sg ON dealer_order (savegame_id, status);
-- one delivery = one VEHICLE_DELIVERY ("zusammen": one per order, "einzeln": one per position). status OPEN (before the
-- order), PLANNED, SENT, WAITING (failed, waits at the dealer), DELIVERED, CANCELLED. delivery_point_id null = shop place.
CREATE TABLE dealer_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    order_id BIGINT NOT NULL REFERENCES dealer_order(id),
    status VARCHAR(16) NOT NULL,
    delivery_point_id BIGINT,
    express BOOLEAN NOT NULL,
    delivery_fee BIGINT DEFAULT 0 NOT NULL,
    agreed_game_time BIGINT,
    due_game_time BIGINT,
    delay_checked BOOLEAN DEFAULT FALSE NOT NULL,
    delayed BOOLEAN DEFAULT FALSE NOT NULL,
    carries_trade_ins BOOLEAN DEFAULT FALSE NOT NULL,
    instruction_id VARCHAR(64),
    sent_as_retry BOOLEAN DEFAULT FALSE NOT NULL,
    sent_fee BIGINT,
    last_failure VARCHAR(64),
    last_failure_detail VARCHAR(200),
    failure_mailed BOOLEAN DEFAULT FALSE NOT NULL,
    attempts INT DEFAULT 0 NOT NULL,
    place_used VARCHAR(16),
    place_name VARCHAR(60),
    delivered_game_time BIGINT,
    vehicle_ids VARCHAR(1000)
);
CREATE INDEX ix_dealer_delivery_sg ON dealer_delivery (savegame_id, status);
-- a position of an order: the cart choice, the options with saveId for VEHICLE_DELIVERY, list price, express fee
-- (5 % of the list price when chosen), L1/L2 delivery span, its share of the agreed price and of the deposit.
CREATE TABLE dealer_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    order_id BIGINT NOT NULL REFERENCES dealer_order(id),
    delivery_id BIGINT NOT NULL REFERENCES dealer_delivery(id),
    position INT NOT NULL,
    xml_filename VARCHAR(512) NOT NULL,
    name VARCHAR(200) NOT NULL,
    choice_json VARCHAR(4000) NOT NULL,
    options_json VARCHAR(4000) NOT NULL,
    list_price BIGINT NOT NULL,
    express_fee BIGINT DEFAULT 0 NOT NULL,
    days_min DOUBLE NOT NULL,
    days_max DOUBLE NOT NULL,
    price_share BIGINT,
    deposit_share BIGINT,
    status VARCHAR(16) NOT NULL
);
-- a trade-in of an order: game value and repair price, the dealer's offer for it; status OPEN, REMOVED, LOST.
CREATE TABLE dealer_trade_in (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    order_id BIGINT NOT NULL REFERENCES dealer_order(id),
    vehicle_id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    game_value BIGINT NOT NULL,
    offer_value BIGINT DEFAULT 0 NOT NULL,
    repair_price_at_agreement BIGINT,
    status VARCHAR(16) NOT NULL
);
