-- Logic review LR-M11: real time of the ack (acked_at_game_time is the game time of the mod). A debit refused for lack
-- of money blocks the snapshot balance until a newer snapshot (T-03); a refusal acknowledged before a detected rewind
-- (bridge_rewind.created_at) belongs to the discarded game time and no longer blocks. Null for acks before V56.
ALTER TABLE outbox_instruction ADD COLUMN acked_at TIMESTAMP;
