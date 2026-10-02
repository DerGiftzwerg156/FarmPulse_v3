-- Roadmap V3 R3-L1: leasing out own fields to neighbours.
-- The game shows the field without owner (the base game farms it as NPC field), the tool keeps the player as owner.
ALTER TABLE farmland_ownership ADD COLUMN leased_from_player BOOLEAN NOT NULL DEFAULT FALSE;
-- Term of a lease-out negotiation (the rent per ha and month is negotiated, the term is fixed by the form).
ALTER TABLE negotiation ADD COLUMN lease_term_months INT;
-- The bank agreed to lease out a pledged field (the Grundschuld stays).
ALTER TABLE loan_collateral ADD COLUMN lease_consent BOOLEAN NOT NULL DEFAULT FALSE;
