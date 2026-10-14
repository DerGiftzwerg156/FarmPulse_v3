-- R31-B5 expert settings of the sickness and work accidents, per savegame (settings, tab "Experte"):
-- the cap of one absence in game days (NULL = automatic only; the absence is scaled to the FS25 days per period, see
-- rpsim.formulas.sick-leave.scale-with-month-length) and the frequency of sickness / accidents in percent.
ALTER TABLE savegame ADD COLUMN sick_leave_max_days INT;
ALTER TABLE savegame ADD COLUMN sickness_frequency_percent INT DEFAULT 100 NOT NULL;
ALTER TABLE savegame ADD COLUMN accident_frequency_percent INT DEFAULT 100 NOT NULL;
