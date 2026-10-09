-- Roadmap V3.2 R32-I: large investors. An offer (service case INVESTOR_OFFER) carries 2-3 packages; each package is an
-- investor_contract (status OFFERED) with its considerations (investor_obligation). The accepted package becomes ACTIVE.
CREATE TABLE investor_contract (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    case_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES game_character(id),
    kind VARCHAR(32) NOT NULL,
    package_no INT NOT NULL,
    amount BIGINT NOT NULL,
    capital_type VARCHAR(16) NOT NULL,
    years INT NOT NULL,
    target_return DOUBLE NOT NULL,
    target_value BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    extension_of BIGINT,
    extended_by BIGINT,
    start_month_index BIGINT NOT NULL,
    end_month_index BIGINT NOT NULL,
    start_year INT NOT NULL,
    offered_game_time BIGINT NOT NULL,
    accepted_game_time BIGINT,
    breaches INT DEFAULT 0 NOT NULL,
    announced BOOLEAN DEFAULT FALSE NOT NULL,
    repayment_due BOOLEAN DEFAULT FALSE NOT NULL,
    ended_game_time BIGINT,
    end_reason VARCHAR(32)
);
CREATE INDEX ix_investor_contract_sg ON investor_contract (savegame_id);

-- One consideration of a package (W1-W3 goods and milk, R1/R2 money, A1-A4 animals and obligations, P1-P5 rights).
CREATE TABLE investor_obligation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    contract_id BIGINT NOT NULL REFERENCES investor_contract(id),
    type VARCHAR(4) NOT NULL,
    main BOOLEAN NOT NULL,
    fill_type VARCHAR(64),
    sub_type VARCHAR(64),
    quantity BIGINT,
    min_per_year BIGINT,
    hectares DOUBLE,
    rate DOUBLE,
    target DOUBLE,
    target_kind VARCHAR(16),
    unit_price DOUBLE,
    value_per_year BIGINT NOT NULL,
    total_value BIGINT NOT NULL,
    delivered_total BIGINT DEFAULT 0 NOT NULL,
    consents VARCHAR(1000),
    fulfilled BOOLEAN DEFAULT FALSE NOT NULL
);
CREATE INDEX ix_investor_obligation_contract ON investor_obligation (contract_id);

-- One billing period of a consideration (month index for monthly ones, FS25 year for yearly ones) and its breach.
CREATE TABLE investor_period (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    obligation_id BIGINT NOT NULL REFERENCES investor_obligation(id),
    period_key BIGINT NOT NULL,
    monthly BOOLEAN NOT NULL,
    period_year INT NOT NULL,
    required BIGINT,
    delivered BIGINT DEFAULT 0 NOT NULL,
    health_sum DOUBLE DEFAULT 0 NOT NULL,
    health_samples INT DEFAULT 0 NOT NULL,
    scheduled_month_index BIGINT,
    case_id BIGINT,
    status VARCHAR(16) NOT NULL,
    breach BOOLEAN DEFAULT FALSE NOT NULL,
    reminded_soon BOOLEAN DEFAULT FALSE NOT NULL,
    reminder_case_id BIGINT,
    grace_until BIGINT,
    shortfall BIGINT,
    compensation BIGINT,
    checked_game_time BIGINT
);
CREATE INDEX ix_investor_period_obligation ON investor_period (obligation_id);

-- A delivery of goods, milk or animals (W1, W2, W3, A1); it counts once the mod acknowledged it.
CREATE TABLE investor_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    obligation_id BIGINT NOT NULL REFERENCES investor_obligation(id),
    quantity BIGINT NOT NULL,
    husbandry_unique_id VARCHAR(64),
    instruction_id VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    delivery_year INT,
    sent_game_time BIGINT NOT NULL,
    done_game_time BIGINT
);
CREATE INDEX ix_investor_delivery_obligation ON investor_delivery (obligation_id);

-- Money between farm and investor: capital, payouts (R1/R2), compensations, repayment / claim.
CREATE TABLE investor_payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    contract_id BIGINT NOT NULL REFERENCES investor_contract(id),
    kind VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    payment_year INT,
    game_time BIGINT NOT NULL,
    instruction_id VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    claim_case_id BIGINT,
    note VARCHAR(200)
);
CREATE INDEX ix_investor_payment_contract ON investor_payment (contract_id);

-- R32-I1: investors switched per savegame (settings -> events, default on); FS25 year of the last offer (one per year).
ALTER TABLE savegame ADD COLUMN investors_enabled BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE savegame ADD COLUMN investor_offer_year INT;
-- R32-I1 / R32-G1: the affiliation of an investor (its kind, e.g. "Privatinvestorin / Familienstiftung") or of a bulk
-- buyer (the sell point name of the map) can be longer than 32 characters.
ALTER TABLE game_character ALTER COLUMN affiliation VARCHAR(128);
