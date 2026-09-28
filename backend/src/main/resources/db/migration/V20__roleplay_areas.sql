-- Roadmap V2 R2-E: tax office (E1), authority (E2), family (E3), clubs and festivals (E4).
-- Characters: affiliation (family relation PARENT / PARTNER / CHILD, club SHOOTING_CLUB / FIRE_BRIGADE / SPORTS_CLUB)
-- and the FS25 period of their yearly occasion (birthday, wedding day).
ALTER TABLE game_character ADD COLUMN affiliation VARCHAR(32);
ALTER TABLE game_character ADD COLUMN occasion_period INT;

-- E1: one row per FS25 year - the assessment with its traceable calculation.
CREATE TABLE tax_year (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    tax_year INT NOT NULL,
    start_game_time BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    months INT DEFAULT 0 NOT NULL,
    operating_income BIGINT DEFAULT 0 NOT NULL,
    operating_expense BIGINT DEFAULT 0 NOT NULL,
    depreciation BIGINT DEFAULT 0 NOT NULL,
    interest BIGINT DEFAULT 0 NOT NULL,
    profit BIGINT DEFAULT 0 NOT NULL,
    allowance BIGINT DEFAULT 0 NOT NULL,
    taxable BIGINT DEFAULT 0 NOT NULL,
    tax_rate DOUBLE PRECISION DEFAULT 0 NOT NULL,
    advisor_reduction BIGINT DEFAULT 0 NOT NULL,
    tax BIGINT DEFAULT 0 NOT NULL,
    prepayments BIGINT DEFAULT 0 NOT NULL,
    balance BIGINT DEFAULT 0 NOT NULL,
    assessed_game_time BIGINT,
    audit_status VARCHAR(32),
    audit_result_game_time BIGINT,
    audit_claim BIGINT,
    CONSTRAINT uq_tax_year UNIQUE (savegame_id, tax_year)
);

-- E2: husbandries with bad values (animal welfare) - since when and how often.
CREATE TABLE husbandry_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    husbandry_unique_id VARCHAR(64) NOT NULL,
    bad_since BIGINT,
    violations INT DEFAULT 0 NOT NULL,
    CONSTRAINT uq_husbandry_record UNIQUE (savegame_id, husbandry_unique_id)
);
-- E2: area of a field (rotation premium per hectare) and repeated rotation violations.
ALTER TABLE field_record ADD COLUMN hectares DOUBLE PRECISION;
ALTER TABLE field_record ADD COLUMN rotation_violations INT DEFAULT 0 NOT NULL;
ALTER TABLE field_record ADD COLUMN duty_violations INT DEFAULT 0 NOT NULL;

-- E2 / E4: monthly caps (inspections, sponsoring requests).
ALTER TABLE savegame ADD COLUMN authority_month BIGINT;
ALTER TABLE savegame ADD COLUMN authority_count INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN last_sponsoring_game_time BIGINT;

-- E3: family chosen in the onboarding, the family field chosen by the player, the monthly retirement payment.
ALTER TABLE savegame ADD COLUMN family_parents BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN family_partner BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN family_children BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN family_field_id INT;
ALTER TABLE savegame ADD COLUMN retirement_payment BIGINT;
