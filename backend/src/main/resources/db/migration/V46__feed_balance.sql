-- Roadmap V4 R4-F: the feed balance per animal type and food group (owner decisions 2026-10-13).
-- R4-F1: per husbandry and fill type the eaten litres since the backend knows the husbandry (total) - from the
-- feedConsumed counter of the mod (the difference to last_counter; a falling counter is taken back) or, with an older
-- mod, from the sinking feeding trough (last_trough).
CREATE TABLE feed_counter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    husbandry_unique_id VARCHAR(80) NOT NULL,
    fill_type VARCHAR(64) NOT NULL,
    last_counter DOUBLE,
    last_trough DOUBLE,
    total DOUBLE NOT NULL,
    CONSTRAINT uq_feed_counter UNIQUE (savegame_id, husbandry_unique_id, fill_type)
);
-- R4-F1: the totals of a husbandry once per game day (and at its first export) with the need of the game at that time;
-- the measured daily need is the difference to the oldest snapshot of the last measure-days game days.
CREATE TABLE feed_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    husbandry_unique_id VARCHAR(80) NOT NULL,
    game_time BIGINT NOT NULL,
    liters_per_hour DOUBLE,
    totals_json VARCHAR(4000) NOT NULL
);
CREATE INDEX ix_feed_snapshot_sg ON feed_snapshot (savegame_id, husbandry_unique_id, game_time);
-- R4-F2: last seen levels (trough per husbandry and fill type, straw per husbandry, silo stock per fill type) - the rise
-- of a trough the silos do not explain came from bales and lowers the entered stock.
CREATE TABLE feed_level (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    ref VARCHAR(160) NOT NULL,
    amount DOUBLE NOT NULL,
    CONSTRAINT uq_feed_level UNIQUE (savegame_id, ref)
);
-- R4-F2: bales, pallets and other stock the player entered (litres left; bales and size as entered).
CREATE TABLE feed_manual_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    fill_type VARCHAR(64) NOT NULL,
    liters DOUBLE NOT NULL,
    bales INT,
    bale_size VARCHAR(32),
    note VARCHAR(200),
    entered_game_time BIGINT NOT NULL,
    checked_game_time BIGINT NOT NULL
);
CREATE INDEX ix_feed_manual_stock_sg ON feed_manual_stock (savegame_id);
-- R4-F4: last warning mail per animal type and food group (at most one per game month and group).
CREATE TABLE feed_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    animal_type VARCHAR(64) NOT NULL,
    group_title VARCHAR(120) NOT NULL,
    last_mail_game_time BIGINT,
    CONSTRAINT uq_feed_alert UNIQUE (savegame_id, animal_type, group_title)
);
