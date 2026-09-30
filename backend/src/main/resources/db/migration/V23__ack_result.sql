-- Roadmap V3 (R3-Q1): optional result the mod reports with an ack (vehicleId after VEHICLE_SPAWN, missionId after
-- MISSION_CREATE), kept as JSON so the features can read it later.
ALTER TABLE outbox_instruction ADD COLUMN ack_result_json VARCHAR(1000);
