-- Roadmap V4 R4-A: the crop planner (owner decisions 2026-10-13). One plan per field and season: OPEN until the
-- season it is for begins with a crop, then MATCHED (the planned crop) or DEVIATED (another crop). The forecast of the
-- plan is kept when the season begins (planned_*) so that "Plan / Ist" compares with what was planned then.
CREATE TABLE crop_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    version BIGINT DEFAULT 0 NOT NULL,
    farmland_id INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    fruit_type VARCHAR(64),
    fill_type VARCHAR(64),
    sow_period INT,
    note VARCHAR(200),
    created_game_time BIGINT NOT NULL,
    target_entry_id BIGINT,
    after_entry_id BIGINT,
    entry_id BIGINT,
    started_game_time BIGINT,
    planned_hectares DOUBLE,
    planned_liters_per_ha DOUBLE,
    planned_yield_source VARCHAR(16),
    planned_price DOUBLE,
    planned_margin DOUBLE,
    reminded_year INT
);
CREATE INDEX ix_crop_plan_sg ON crop_plan (savegame_id, farmland_id);
-- R4-A5 (owner decision 2026-10-13): reminder mail of the cooperative at sowing time, null = default (on);
-- R4-A6: the FS25 year the plan was last discussed with the cooperative (once per plan year).
ALTER TABLE savegame ADD COLUMN crop_plan_reminders BOOLEAN;
ALTER TABLE savegame ADD COLUMN crop_plan_discussed_year INT;
