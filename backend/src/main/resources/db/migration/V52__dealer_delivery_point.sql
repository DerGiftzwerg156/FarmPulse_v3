-- Roadmap vehicle dealer RFH-O1 (owner decisions 2026-10-15): the delivery points the player set in the game by key
-- (farm_facts.deliveryPoints). Each seq of the mod is taken over once; a deleted point stays as a row (deleted = true)
-- so that the mod's next export does not bring it back. name = null until the player names it. The default delivery
-- point of the savegame: null = the shop place of the game.
CREATE TABLE dealer_delivery_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    seq INT NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    z DOUBLE NOT NULL,
    ry DOUBLE NOT NULL,
    farmland_id INT,
    set_day INT,
    set_day_time_ms BIGINT,
    name VARCHAR(40),
    deleted BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT uq_dealer_delivery_point UNIQUE (savegame_id, seq)
);
ALTER TABLE savegame ADD COLUMN dealer_default_point_id BIGINT;
