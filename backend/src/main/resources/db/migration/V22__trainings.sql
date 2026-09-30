-- Trainings of machine operators: finished trainings (comma separated Training names), the running training and its end,
-- and the training an applicant brings along. Existing employees start without trainings (owner decision).
ALTER TABLE employee ADD COLUMN trainings VARCHAR(255) DEFAULT '' NOT NULL;
ALTER TABLE employee ADD COLUMN training_in_progress VARCHAR(32);
ALTER TABLE employee ADD COLUMN training_until_game_time BIGINT;
ALTER TABLE job_application ADD COLUMN training VARCHAR(32);
