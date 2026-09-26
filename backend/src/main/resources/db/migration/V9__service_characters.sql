-- TODO T-20 / T-22: recurring contracts (insurance, lease, maintenance) and service cases of the new characters.
CREATE TABLE contract (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    character_id BIGINT REFERENCES game_character(id),
    level VARCHAR(32),
    farmland_id INT,
    monthly_amount BIGINT NOT NULL,
    coverage_rate DOUBLE,
    deductible BIGINT,
    term_months INT,
    started_at_game_time BIGINT,
    ends_at_game_time BIGINT,
    next_due_game_time BIGINT,
    offer_expires_at_game_time BIGINT,
    missed_payments INT DEFAULT 0 NOT NULL,
    payment_overdue BOOLEAN DEFAULT FALSE NOT NULL,
    renewal_offered BOOLEAN DEFAULT FALSE NOT NULL,
    end_reason VARCHAR(64),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_contract_sg ON contract (savegame_id, status);

CREATE TABLE service_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    character_id BIGINT REFERENCES game_character(id),
    farmland_id INT,
    hectares DOUBLE,
    damage_amount BIGINT,
    payout_amount BIGINT,
    cost_amount BIGINT,
    offer_amount BIGINT,
    rounds_used INT DEFAULT 0 NOT NULL,
    measure_agreed BOOLEAN DEFAULT FALSE NOT NULL,
    reference VARCHAR(255),
    contract_id BIGINT,
    game_time BIGINT NOT NULL,
    deadline_game_time BIGINT,
    closed_at_game_time BIGINT,
    resolution VARCHAR(64),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_service_case_sg ON service_case (savegame_id, status);
