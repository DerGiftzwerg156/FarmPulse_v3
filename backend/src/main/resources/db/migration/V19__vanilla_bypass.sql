-- Roadmap V2 R2-D: the vanilla loan and the field menu become part of the story.
-- D1: last seen remaining amount of the vanilla loan (and its game time, to detect a reload), increases / repayments
-- waiting for the daily reaction, takings while the loan is open and the resulting interest surcharge.
ALTER TABLE savegame ADD COLUMN vanilla_loan_seen DOUBLE PRECISION;
ALTER TABLE savegame ADD COLUMN vanilla_loan_seen_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN vanilla_loan_pending_taken DOUBLE PRECISION DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN vanilla_loan_pending_repaid DOUBLE PRECISION DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN vanilla_loan_takings INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN vanilla_loan_surcharge BOOLEAN DEFAULT FALSE NOT NULL;
-- D1-D3: the player can switch the reactions off (settings page).
ALTER TABLE savegame ADD COLUMN vanilla_bypass_enabled BOOLEAN DEFAULT TRUE NOT NULL;
-- D3: one-time hint about helpers without employee.
ALTER TABLE savegame ADD COLUMN outside_helpers_hint_sent BOOLEAN DEFAULT FALSE NOT NULL;
