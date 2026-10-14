-- Overdraft (owner decisions 2026-10-14): per savegame the farm may go below zero down to -overdraft_limit with every
-- debit of the tool; off by default with limit 0, so existing savegames keep refusing debits below zero. The interest
-- rate (percent per year) is charged at every month start on a negative balance while the overdraft is on.
ALTER TABLE savegame ADD COLUMN overdraft_enabled BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN overdraft_limit BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN overdraft_interest_percent DOUBLE DEFAULT 10 NOT NULL;
