-- Roadmap V3.1 section A "Arbeit auf dem Hof".
-- R31-A2: borrowed machine of a neighbour (LOAN) or demo machine of the workshop (DEMO).
CREATE TABLE machine_loan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    character_id BIGINT REFERENCES game_character(id),
    store_xml_filename VARCHAR(512) NOT NULL,
    vehicle_name VARCHAR(255),
    category_name VARCHAR(64),
    list_price BIGINT NOT NULL,
    age_months INT NOT NULL,
    operating_hours INT NOT NULL,
    damage DOUBLE NOT NULL,
    wear DOUBLE NOT NULL,
    days INT NOT NULL,
    daily_rent BIGINT NOT NULL,
    vehicle_id VARCHAR(64),
    start_condition DOUBLE,
    last_condition DOUBLE,
    last_value BIGINT,
    delivered_game_time BIGINT,
    ends_game_time BIGINT,
    rent_days_booked INT DEFAULT 0 NOT NULL,
    late_days INT DEFAULT 0 NOT NULL,
    attempts INT DEFAULT 0 NOT NULL,
    next_attempt_game_time BIGINT,
    vehicle_deal_id BIGINT,
    compensation BIGINT,
    end_reason VARCHAR(64),
    created_game_time BIGINT NOT NULL,
    closed_game_time BIGINT
);
CREATE INDEX ix_machine_loan_savegame ON machine_loan(savegame_id, status);

-- R31-A2: a used-machine deal that buys the demo machine standing on the farm (no delivery, only the price).
ALTER TABLE vehicle_deal ADD COLUMN demo_loan_id BIGINT;

-- R31-A3: animals the neighbours keep per animal type (backend fiction, rolled when first needed).
CREATE TABLE neighbor_animal_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT NOT NULL REFERENCES game_character(id),
    animal_type VARCHAR(32) NOT NULL,
    animal_count INT NOT NULL,
    updated_game_time BIGINT NOT NULL,
    CONSTRAINT uq_neighbor_animal_stock UNIQUE (character_id, animal_type)
);

-- R31-A3: origin and direction of a livestock deal (e.g. PLAYER_REQUEST:SELL) in service_case.direction.
ALTER TABLE service_case ALTER COLUMN direction VARCHAR(32);

-- R31-A4: winter service contract - snow days of the running winter month, of the whole winter, last counted game day.
ALTER TABLE contract ADD COLUMN snow_days INT DEFAULT 0 NOT NULL;
ALTER TABLE contract ADD COLUMN snow_days_total INT DEFAULT 0 NOT NULL;
ALTER TABLE contract ADD COLUMN last_snow_day BIGINT;

-- R31-A5: seasonal workers - end of the fixed-term contract, satisfaction at the end of the season, re-application.
ALTER TABLE employee ADD COLUMN contract_ends_at_game_time BIGINT;
ALTER TABLE employee ADD COLUMN season_end_satisfaction DOUBLE PRECISION;
ALTER TABLE job_application ADD COLUMN returning_employee_id BIGINT;
