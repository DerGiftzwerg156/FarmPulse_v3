-- TODO T-21: characters that stand for an FS25 NPC of the map (farmland owner from Farmland.npcIndex).
ALTER TABLE game_character ADD COLUMN fs25_npc_index INT;
