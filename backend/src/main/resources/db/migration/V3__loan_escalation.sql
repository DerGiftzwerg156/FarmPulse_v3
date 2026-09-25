-- AP-4.2: tracks the last missed due date so every missed installment is counted exactly once.
ALTER TABLE loan ADD COLUMN last_missed_due_game_time BIGINT;
