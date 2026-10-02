-- Roadmap V3 R3-P: office clerk with more effect, apprentice.
-- R3-P1: deadlines the office clerk already reminded of (once per subject and deadline).
CREATE TABLE office_reminder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    subject_type VARCHAR(32) NOT NULL,
    subject_id BIGINT NOT NULL,
    deadline_game_time BIGINT NOT NULL,
    sent_game_time BIGINT NOT NULL,
    CONSTRAINT uq_office_reminder UNIQUE (savegame_id, subject_type, subject_id, deadline_game_time)
);
-- R3-P2: end of the training of an apprentice.
ALTER TABLE employee ADD COLUMN apprenticeship_ends_at_game_time BIGINT;
