-- Roadmap V3.2 R32-G: bulk orders. The request itself is a service case (BULK_ORDER); an order with a delivery month
-- becomes a bulk_order (PRICE_EVENT / FIXED at the buyer's sell point in that month, like the forward contract).
CREATE TABLE bulk_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    case_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES game_character(id),
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
    notice_instruction_id VARCHAR(64),
    created_game_time BIGINT NOT NULL,
    delivered_quantity BIGINT,
    end_reason VARCHAR(32),
    penalty BIGINT
);
-- R32-G1: request probability factor of the bulk buyers (refusals and shortfalls lower it, full deliveries raise it).
ALTER TABLE savegame ADD COLUMN bulk_order_factor DOUBLE DEFAULT 1.0 NOT NULL;
