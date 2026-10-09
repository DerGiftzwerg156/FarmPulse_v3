-- Owner decisions 2026-10-06: applications arrive the next game day (arrives_at_game_time, null = already there),
-- a new employee starts with the next month (starts_at_game_time, status PENDING_START until then) and a training takes
-- place on the next game day (training_from_game_time, null = old booking that started at once).
ALTER TABLE job_application ADD COLUMN arrives_at_game_time BIGINT;
ALTER TABLE employee ADD COLUMN starts_at_game_time BIGINT;
ALTER TABLE employee ADD COLUMN training_from_game_time BIGINT;
