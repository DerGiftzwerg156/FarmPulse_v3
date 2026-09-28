-- Roadmap V2 R2-B5: evaluation state of the booking journal (bank early warning, harvest record).
ALTER TABLE savegame ADD COLUMN fin_last_month_key BIGINT;
ALTER TABLE savegame ADD COLUMN fin_months_seen INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN fin_negative_streak INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN fin_warning_sent BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN fin_record_revenue DOUBLE PRECISION;
