-- Roadmap vehicle dealer RFH-K3 (owner decisions 2026-10-15): the cart of the dealer per savegame, at most
-- rpsim.formulas.machine-dealer.max-positions positions. A position is a shop entry with the player's choice of options
-- (type name -> option index as JSON); the same machine may stand several times with other options. The name is kept
-- for a position whose machine is no longer in the catalog (mod removed).
CREATE TABLE dealer_cart_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    xml_filename VARCHAR(512) NOT NULL,
    name VARCHAR(200) NOT NULL,
    configurations_json VARCHAR(4000) NOT NULL,
    created_game_time BIGINT NOT NULL
);
CREATE INDEX ix_dealer_cart_item_sg ON dealer_cart_item (savegame_id);
