-- Roadmap V3.3 R33-F: the field book. One entry per own field and season: RUNNING ("laufende Saison", no year yet),
-- HARVESTED or NO_HARVEST with the FS25 year of its end (harvest year). Every value has the detected (auto) and the
-- corrected (manual) value; manual wins and can be given back (owner decisions 2026-10-08 / 2026-10-09).
CREATE TABLE field_book_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    farmland_id INT NOT NULL,
    field_name VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    harvest_year INT,
    hectares DOUBLE,
    first_entry BOOLEAN DEFAULT FALSE NOT NULL,
    held BOOLEAN DEFAULT FALSE NOT NULL,
    started_game_time BIGINT NOT NULL,
    ended_game_time BIGINT,
    fruit_type_auto VARCHAR(64),
    fruit_type_manual VARCHAR(64),
    fill_type_auto VARCHAR(64),
    fill_type_manual VARCHAR(64),
    liters_auto DOUBLE,
    liters_manual DOUBLE,
    fert_count_auto INT DEFAULT 0 NOT NULL,
    fert1_manual BOOLEAN,
    fert2_manual BOOLEAN,
    limed_auto BOOLEAN DEFAULT FALSE NOT NULL,
    limed_manual BOOLEAN,
    rolled_auto BOOLEAN DEFAULT FALSE NOT NULL,
    rolled_manual BOOLEAN,
    weeds_auto BOOLEAN DEFAULT FALSE NOT NULL,
    weeds_manual BOOLEAN,
    mulched_auto BOOLEAN DEFAULT FALSE NOT NULL,
    mulched_manual BOOLEAN,
    spray_types VARCHAR(255),
    last_phase VARCHAR(16),
    last_growth_state INT,
    last_spray_level INT,
    last_lime_level INT,
    last_roller_level INT,
    last_weed_state INT,
    last_stubble_level INT
);
CREATE INDEX ix_field_book_entry_sg ON field_book_entry (savegame_id, farmland_id);
-- R33-F5: closed harvest years (locked for changes and for the automatic capture).
CREATE TABLE field_book_year (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    harvest_year INT NOT NULL,
    closed_game_time BIGINT NOT NULL,
    CONSTRAINT uq_field_book_year UNIQUE (savegame_id, harvest_year)
);
-- R33-F5: litres the game counted for an entry of a closed year; taken over when the year is reopened.
CREATE TABLE field_book_notice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    entry_id BIGINT NOT NULL,
    liters DOUBLE NOT NULL,
    created_game_time BIGINT NOT NULL
);
CREATE INDEX ix_field_book_notice_sg ON field_book_notice (savegame_id);
-- R33-F3: last seen value of every harvest counter of the mod (farm_facts.harvests); the difference is booked.
CREATE TABLE field_book_counter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    farmland_id INT NOT NULL,
    fruit_type VARCHAR(64) NOT NULL,
    fill_type VARCHAR(64) NOT NULL,
    last_liters DOUBLE NOT NULL,
    CONSTRAINT uq_field_book_counter UNIQUE (savegame_id, farmland_id, fruit_type, fill_type)
);
