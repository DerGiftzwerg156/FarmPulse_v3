-- AP-4.9: cadence limits of the village-life module.
ALTER TABLE savegame ADD COLUMN last_congratulation_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN last_gossip_game_time BIGINT;
