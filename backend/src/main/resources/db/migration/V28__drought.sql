-- Roadmap V3 R3-W: drought, drought aid, weather-index insurance.
-- R3-W1: the current series of dry growth months (count, first dry month, drought already declared).
ALTER TABLE savegame ADD COLUMN drought_dry_months INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN drought_series_start_month BIGINT;
ALTER TABLE savegame ADD COLUMN drought_series_declared BOOLEAN DEFAULT FALSE NOT NULL;
-- R3-W2: own fields with a crop in phase GROWING per game month (base of the drought aid).
CREATE TABLE growing_field_month (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    month_index BIGINT NOT NULL,
    farmland_id INT NOT NULL,
    field_name VARCHAR(128),
    fruit_type VARCHAR(64),
    hectares DOUBLE NOT NULL,
    CONSTRAINT uq_growing_field_month UNIQUE (savegame_id, month_index, farmland_id)
);
-- R3-W1..W3: a declared drought with its consequences.
CREATE TABLE drought (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    first_month_index BIGINT NOT NULL,
    last_month_index BIGINT NOT NULL,
    declared_game_time BIGINT NOT NULL,
    crops VARCHAR(255),
    price_events INT DEFAULT 0 NOT NULL,
    insurance_result VARCHAR(32) NOT NULL,
    insurance_contract_id BIGINT,
    insured_hectares DOUBLE,
    insurance_payout BIGINT,
    aid_hectares DOUBLE,
    aid_case_id BIGINT
);
