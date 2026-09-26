-- TODO T-21: season of the FS25 calendar (name from the game's Season table) for calendar references in texts.
ALTER TABLE savegame ADD COLUMN cal_season VARCHAR(16);
