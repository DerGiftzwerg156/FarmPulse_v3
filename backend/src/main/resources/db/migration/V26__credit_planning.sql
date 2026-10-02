-- Roadmap V3 R3-K: loan collateral, liquidity plan, farm report and annual review.
-- R3-K1: own fields pledged for a loan (Grundschuld). Requested / proposed rows belong to a credit application, pledged
-- rows to the loan; the collateral value is fixed when the application is scored.
CREATE TABLE loan_collateral (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    application_id BIGINT REFERENCES credit_application(id),
    loan_id BIGINT REFERENCES loan(id),
    farmland_id INT NOT NULL,
    collateral_value BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    sale_consent BOOLEAN DEFAULT FALSE NOT NULL,
    release_instruction_id VARCHAR(64),
    pledged_game_time BIGINT,
    released_game_time BIGINT
);
CREATE INDEX ix_loan_collateral_farmland ON loan_collateral (savegame_id, farmland_id, status);
ALTER TABLE credit_application ADD COLUMN collateral_value BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE credit_application ADD COLUMN collateral_coverage DOUBLE DEFAULT 0 NOT NULL;
ALTER TABLE credit_application ADD COLUMN interest_discount DOUBLE DEFAULT 0 NOT NULL;
ALTER TABLE credit_application ADD COLUMN collateral_required BOOLEAN DEFAULT FALSE NOT NULL;
-- R3-K3: rate cuts of the annual review accumulated per loan (capped).
ALTER TABLE loan ADD COLUMN rate_cut_total DOUBLE DEFAULT 0 NOT NULL;
-- R3-K3: yield of a crop = area x litersPerSqm of the last ripe sighting, stored when it is harvested.
ALTER TABLE field_crop_history ADD COLUMN ripe_liters DOUBLE;
ALTER TABLE field_crop_history ADD COLUMN yield_liters DOUBLE;
-- R3-K2: month index of the shortfall the bank advisor last warned about (once per shortfall).
ALTER TABLE savegame ADD COLUMN liquidity_warning_month BIGINT;
-- R3-K3: farm report of a finished FS25 year (figures as JSON, snapshot of the key figures for the next comparison).
CREATE TABLE farm_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    report_year INT NOT NULL,
    created_game_time BIGINT NOT NULL,
    report_json CLOB NOT NULL,
    CONSTRAINT uq_farm_report UNIQUE (savegame_id, report_year)
);
