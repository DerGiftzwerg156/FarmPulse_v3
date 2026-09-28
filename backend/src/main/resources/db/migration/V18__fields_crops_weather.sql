-- Roadmap V2 R2-C: fields, crops and weather.
-- One row per own field (farmland) with the derived growth phase and the timers of the village reactions (C4) and
-- field hints (C6).
CREATE TABLE field_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    farmland_id INT NOT NULL,
    field_name VARCHAR(64),
    fruit_type VARCHAR(64),
    phase VARCHAR(16) NOT NULL,
    phase_since_game_time BIGINT NOT NULL,
    last_seen_game_time BIGINT NOT NULL,
    weeds_high_since BIGINT,
    stones_high_since BIGINT,
    neighbor_warnings INT DEFAULT 0 NOT NULL,
    last_neighbor_warning_game_time BIGINT,
    fallow_gossip_sent BOOLEAN DEFAULT FALSE NOT NULL,
    withered_gossip_sent BOOLEAN DEFAULT FALSE NOT NULL,
    harvest_hint_sent BOOLEAN DEFAULT FALSE NOT NULL,
    lime_hint_sent BOOLEAN DEFAULT FALSE NOT NULL,
    plow_hint_sent BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT uq_field_record UNIQUE (savegame_id, farmland_id)
);
-- Crop per field and FS25 year (C1 history, needed for E2): was it harvestable, harvested in time or withered?
CREATE TABLE field_crop_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    farmland_id INT NOT NULL,
    crop_year INT NOT NULL,
    fruit_type VARCHAR(64) NOT NULL,
    first_seen_game_time BIGINT NOT NULL,
    harvestable_seen BOOLEAN DEFAULT FALSE NOT NULL,
    harvested BOOLEAN DEFAULT FALSE NOT NULL,
    withered BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT uq_field_crop_history UNIQUE (savegame_id, farmland_id, crop_year, fruit_type)
);
-- Rain hours per game month (C2), extrapolated from the weather samples of the exports.
CREATE TABLE rain_period (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    month_index BIGINT NOT NULL,
    rain_ms BIGINT DEFAULT 0 NOT NULL,
    observed_ms BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT uq_rain_period UNIQUE (savegame_id, month_index)
);
ALTER TABLE savegame ADD COLUMN last_weather_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN last_weather_raining BOOLEAN;
ALTER TABLE savegame ADD COLUMN fields_tracked BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN field_year INT;
-- C4 / C6: monthly cap of the field messages, the weekly field hint and its switch (settings page).
ALTER TABLE savegame ADD COLUMN field_messages_month BIGINT;
ALTER TABLE savegame ADD COLUMN field_messages_count INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN last_field_hint_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN field_hints_enabled BOOLEAN DEFAULT TRUE NOT NULL;
