-- Roadmap vehicle dealer RFH-V1 (owner decisions 2026-10-15): the dealer's demo machine is a machine_loan of kind
-- DEALER_DEMO (VEHICLE_SPAWN price 0 on the shop place, VEHICLE_REMOVE after 2 days of a 30-day month). game_year =
-- the game year of the request (each machine once per game year); null for the loans and demos of R31-A2.
ALTER TABLE machine_loan ADD COLUMN game_year INTEGER;
