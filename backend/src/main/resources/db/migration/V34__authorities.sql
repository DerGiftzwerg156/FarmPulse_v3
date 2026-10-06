-- Roadmap V3.1 section B "Behörden und Förderung".
-- R31-B: burdening events switched per savegame (default on).
ALTER TABLE savegame ADD COLUMN burden_area_check BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE savegame ADD COLUMN burden_fertilizer BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE savegame ADD COLUMN burden_disease BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE savegame ADD COLUMN burden_sick_leave BOOLEAN DEFAULT TRUE NOT NULL;

-- R31-B1: area payment application of an FS25 year and its fields.
CREATE TABLE direct_payment_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT REFERENCES game_character(id),
    crop_year INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    opened_game_time BIGINT NOT NULL,
    deadline_game_time BIGINT NOT NULL,
    submitted_game_time BIGINT,
    late_days INT DEFAULT 0 NOT NULL,
    check_status VARCHAR(32) NOT NULL,
    check_case_id BIGINT,
    deviating_hectares DOUBLE,
    deviation_cut BIGINT,
    rotation_cut BIGINT,
    late_cut BIGINT,
    premium BIGINT,
    paid_amount BIGINT,
    closed_game_time BIGINT
);
CREATE INDEX ix_direct_payment_savegame ON direct_payment_application(savegame_id, crop_year);

CREATE TABLE direct_payment_field (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    application_id BIGINT NOT NULL,
    farmland_id INT NOT NULL,
    field_name VARCHAR(255),
    hectares DOUBLE NOT NULL,
    declared_crop VARCHAR(64) NOT NULL,
    actual_crop VARCHAR(64),
    rotation_repeat BOOLEAN DEFAULT FALSE NOT NULL
);
CREATE INDEX ix_direct_payment_field_application ON direct_payment_field(application_id);

-- R31-B2: investment grant and the funded machines (binding period).
CREATE TABLE investment_grant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT REFERENCES game_character(id),
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    planned_sum BIGINT NOT NULL,
    applied_game_time BIGINT NOT NULL,
    approval_due_game_time BIGINT NOT NULL,
    approved_game_time BIGINT,
    purchase_deadline_game_time BIGINT,
    recognised_sum BIGINT DEFAULT 0 NOT NULL,
    buy_month_key BIGINT,
    buy_month_amount DOUBLE,
    sell_month_key BIGINT,
    sell_month_amount DOUBLE,
    baseline_vehicles CLOB,
    grant_amount BIGINT,
    paid_game_time BIGINT,
    binding_ends_game_time BIGINT,
    repaid_amount BIGINT DEFAULT 0 NOT NULL,
    closed_game_time BIGINT
);
CREATE INDEX ix_investment_grant_savegame ON investment_grant(savegame_id, status);

CREATE TABLE investment_grant_object (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    grant_id BIGINT NOT NULL,
    vehicle_unique_id VARCHAR(64) NOT NULL,
    vehicle_value DOUBLE NOT NULL,
    seen_game_time BIGINT NOT NULL,
    sold_game_time BIGINT,
    repayment BIGINT,
    repayment_case_id BIGINT
);
CREATE INDEX ix_investment_grant_object_grant ON investment_grant_object(grant_id);

-- R31-B3: last spray type / level per own field, slurry store per stable, confirmed fertiliser findings.
ALTER TABLE field_record ADD COLUMN last_spray_type VARCHAR(32);
ALTER TABLE field_record ADD COLUMN last_spray_level INT;
ALTER TABLE husbandry_record ADD COLUMN slurry_high_since BIGINT;
ALTER TABLE husbandry_record ADD COLUMN last_slurry_warning_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN fertilizer_violations INT DEFAULT 0 NOT NULL;

-- R31-B4: animal disease with a restricted zone.
CREATE TABLE animal_disease (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    disease_key VARCHAR(32) NOT NULL,
    animal_types VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    declared_game_time BIGINT NOT NULL,
    declared_month_index BIGINT NOT NULL,
    ends_month_index BIGINT NOT NULL,
    lifted_game_time BIGINT,
    lifted_month_index BIGINT
);
CREATE INDEX ix_animal_disease_savegame ON animal_disease(savegame_id, status);

-- R31-B5: sickness / work accident of an employee (ON_LEAVE for the mod) and the get-well wishes.
ALTER TABLE employee ADD COLUMN absence_kind VARCHAR(32);
ALTER TABLE employee ADD COLUMN absence_until_game_time BIGINT;
ALTER TABLE employee ADD COLUMN get_well_sent BOOLEAN DEFAULT FALSE NOT NULL;
