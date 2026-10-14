-- R31-B5: the player's cap of one sickness / accident absence in game days (settings); NULL = automatic only (the
-- absence is scaled to the FS25 days per period, see rpsim.formulas.sick-leave.scale-with-month-length).
ALTER TABLE savegame ADD COLUMN sick_leave_max_days INT;
