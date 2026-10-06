-- First-open hints of the Hof-Tablet apps (owner decision 2026-10-06): an app's hint is shown once per installation,
-- on any device. One row per app whose hint was confirmed with "Verstanden" - not part of a savegame.
CREATE TABLE app_hint_seen (
    app_id VARCHAR(32) PRIMARY KEY,
    seen_at TIMESTAMP NOT NULL
);
