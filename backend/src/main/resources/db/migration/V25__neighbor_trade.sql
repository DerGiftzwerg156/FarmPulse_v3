-- Roadmap V3 R3-H: trade with the neighbours and their real contracts.
-- Role of a neighbour (owner decision: DAIRY / ARABLE / MIXED, rolled when the neighbour is created or first needed).
ALTER TABLE game_character ADD COLUMN neighbor_role VARCHAR(32);
-- uniqueId of the contract a neighbour mission created in the game (ack result of MISSION_CREATE).
ALTER TABLE service_case ADD COLUMN external_id VARCHAR(64);
-- R3-H1: one row per field the game's NPCs farm, with the derived growth phase (like field_record for own fields).
CREATE TABLE npc_field_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    farmland_id INT NOT NULL,
    field_name VARCHAR(64),
    hectares DOUBLE,
    fruit_type VARCHAR(64),
    fill_type VARCHAR(64),
    liters_per_sqm DOUBLE,
    plow_level INT,
    stone_level INT,
    phase VARCHAR(16) NOT NULL,
    phase_since_game_time BIGINT NOT NULL,
    last_seen_game_time BIGINT NOT NULL,
    CONSTRAINT uq_npc_field_record UNIQUE (savegame_id, farmland_id)
);
-- R3-H1: crop of a neighbour field per FS25 year (harvested or not).
CREATE TABLE npc_field_crop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    farmland_id INT NOT NULL,
    crop_year INT NOT NULL,
    fruit_type VARCHAR(64) NOT NULL,
    first_seen_game_time BIGINT NOT NULL,
    harvested BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT uq_npc_field_crop UNIQUE (savegame_id, farmland_id, crop_year, fruit_type)
);
-- R3-H2: stock of a neighbour per fill type (litres), fed by his harvests, sinking every game month.
CREATE TABLE neighbor_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT NOT NULL REFERENCES game_character(id),
    fill_type VARCHAR(64) NOT NULL,
    amount DOUBLE NOT NULL,
    updated_game_time BIGINT NOT NULL,
    CONSTRAINT uq_neighbor_stock UNIQUE (character_id, fill_type)
);
