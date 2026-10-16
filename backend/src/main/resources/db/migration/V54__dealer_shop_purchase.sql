-- Roadmap vehicle dealer RFH-S2 (owner decisions 2026-10-15/16): the dealer reacts once to every purchase in the game
-- shop (SHOP_VEHICLE_BUY). Entries stored before this migration count as handled (no reaction to old purchases).
ALTER TABLE booking_entry ADD COLUMN dealer_reacted BOOLEAN DEFAULT TRUE NOT NULL;
