-- Booking statement ("Kontoauszug", owner decisions 2026-10-06): the single bookings of farm_facts.bookings, stored
-- permanently. seq = running number of the mod (unique per savegame); an entry the mod keeps summing up is updated in
-- place, entries at or after the mod's nextSeq are deleted (reload without saving).
CREATE TABLE booking_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    seq BIGINT NOT NULL,
    game_time BIGINT NOT NULL,
    booking_year INT NOT NULL,
    period INT NOT NULL,
    day_in_period INT,
    category VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    booking_count INT DEFAULT 1 NOT NULL,
    single_entry BOOLEAN DEFAULT FALSE NOT NULL,
    liters BIGINT,
    fill_type VARCHAR(64),
    sell_point VARCHAR(128),
    note VARCHAR(255),
    -- shop purchases / sales of vehicles: names of the vehicles that appeared / disappeared in the same export
    vehicle_match VARCHAR(16),
    vehicle_names VARCHAR(1000),
    match_exports INT DEFAULT 0 NOT NULL
);
CREATE UNIQUE INDEX ux_booking_entry_seq ON booking_entry(savegame_id, seq);
CREATE INDEX ix_booking_entry_month ON booking_entry(savegame_id, booking_year, period);
