-- TODO T-22: lease of NPC fields - the field stays the NPC's in the tool while the game gives it to the player.
ALTER TABLE farmland_ownership ADD COLUMN leased_to_player BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE contract ADD COLUMN renewal_amount BIGINT;
ALTER TABLE contract ADD COLUMN purchase_price BIGINT;
