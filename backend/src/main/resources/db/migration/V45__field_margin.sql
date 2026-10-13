-- Roadmap V4 R4-D: the contribution margin per field book entry (owner decisions 2026-10-13).
-- One line per entry and kind: the consumption of a fill type counted by the mod (MEASURED, one line per fill type),
-- contractor fees, rent and premiums (MEASURED, one line per booking), overheads distributed by area (DISTRIBUTED, one
-- line per month) and lines the player added (ENTERED). Amounts are signed: costs negative, income positive. The
-- correction (manual) wins and can be given back.
CREATE TABLE field_margin_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    entry_id BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    source VARCHAR(16) NOT NULL,
    reference VARCHAR(80),
    fill_type VARCHAR(64),
    liters_auto DOUBLE,
    helper_liters DOUBLE,
    amount_auto DOUBLE,
    liters_manual DOUBLE,
    amount_manual DOUBLE,
    note VARCHAR(200),
    held BOOLEAN DEFAULT FALSE NOT NULL,
    game_time BIGINT NOT NULL
);
CREATE INDEX ix_field_margin_item_sg ON field_margin_item (savegame_id, entry_id);
-- R4-D2: per entry the best price of its product on the harvest day and the price the player entered.
CREATE TABLE field_margin_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    entry_id BIGINT NOT NULL,
    ended_game_time BIGINT,
    harvest_price DOUBLE,
    price_manual DOUBLE,
    advised BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT uq_field_margin_entry UNIQUE (savegame_id, entry_id)
);
-- R4-D1: last seen value of every consumption counter of the mod (farm_facts.inputs); the difference is booked.
CREATE TABLE field_input_counter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    farmland_id INT NOT NULL,
    fill_type VARCHAR(64) NOT NULL,
    last_liters DOUBLE NOT NULL,
    last_helper_liters DOUBLE NOT NULL,
    CONSTRAINT uq_field_input_counter UNIQUE (savegame_id, farmland_id, fill_type)
);
-- R4-D3 (owner decision 2026-10-13): switch "Gemeinkosten verteilen" per savegame; null = default (on).
ALTER TABLE savegame ADD COLUMN field_margin_overheads BOOLEAN;
