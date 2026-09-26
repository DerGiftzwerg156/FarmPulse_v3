-- TODO T-21: season names come from the game's Season table - allow longer (mod-defined) names.
ALTER TABLE savegame ALTER COLUMN cal_season VARCHAR(64);
