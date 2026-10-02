-- Roadmap V3 R3-T: milestones and the farm chronicle.
-- R3-T1: reached milestones (each once per savegame).
CREATE TABLE milestone (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    milestone_key VARCHAR(32) NOT NULL,
    reached_game_time BIGINT NOT NULL,
    diary_entry_id BIGINT,
    CONSTRAINT uq_milestone UNIQUE (savegame_id, milestone_key)
);
-- R3-T1: payment delays (missed installment, overdue tax bill, missed contract payment, overdue salary, unpaid claim).
CREATE TABLE payment_delay (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    game_time BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL
);
CREATE INDEX ix_payment_delay_time ON payment_delay (savegame_id, game_time);
-- R3-T1: payment delays count from this game time on (a year counts only when fully observed).
ALTER TABLE savegame ADD COLUMN milestone_watch_from BIGINT;
-- R3-T1: closed harvest years in a row without a crop-rotation complaint.
ALTER TABLE savegame ADD COLUMN rotation_clean_years INT NOT NULL DEFAULT 0;
-- R3-T2: optional farm name (settings, onboarding).
ALTER TABLE savegame ADD COLUMN farm_name VARCHAR(60);
