-- Roadmap V2 R2-A: employees as FS25 helpers.
-- Savegame: switches of the helper wage (A1) and the strict helper limit (A3), last employee list sent to the mod (A0),
-- whether the mod reports worked time (A4).
ALTER TABLE savegame ADD COLUMN helper_wage_mode VARCHAR(16) DEFAULT 'EMPLOYEES' NOT NULL;
ALTER TABLE savegame ADD COLUMN strict_helper_limit BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN roster_json CLOB;
ALTER TABLE savegame ADD COLUMN roster_sent_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN workforce_tracked BOOLEAN DEFAULT FALSE NOT NULL;
-- Employee: strike (A5) and worked game time from the mod (A4), counted per day and per game month.
ALTER TABLE employee ADD COLUMN strike_since_game_time BIGINT;
ALTER TABLE employee ADD COLUMN worked_ms_seen BIGINT;
ALTER TABLE employee ADD COLUMN worked_ms_today BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE employee ADD COLUMN worked_ms_month BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE employee ADD COLUMN worked_ms_last_month BIGINT DEFAULT 0 NOT NULL;
-- R2-A7: the mod reports husbandry values (farm_facts.husbandries) - the workload of animal keepers follows the animals.
ALTER TABLE savegame ADD COLUMN husbandries_tracked BOOLEAN DEFAULT FALSE NOT NULL;
-- R2-A7: last warning mail of an animal keeper about food / water (cooldown).
ALTER TABLE employee ADD COLUMN last_stable_warning_game_time BIGINT;
