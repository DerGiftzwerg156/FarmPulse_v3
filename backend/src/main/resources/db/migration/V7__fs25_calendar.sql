-- TODO T-08: game month = FS25 period; calendar of the savegame from farm_facts.json.
ALTER TABLE savegame ADD COLUMN cal_month_index BIGINT;
ALTER TABLE savegame ADD COLUMN cal_month_start_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN cal_days_per_period INT;
ALTER TABLE savegame ADD COLUMN cal_period INT;
ALTER TABLE savegame ADD COLUMN cal_day_in_period INT;
ALTER TABLE savegame ADD COLUMN cal_year INT;
ALTER TABLE savegame ADD COLUMN cal_period_name VARCHAR(64);
