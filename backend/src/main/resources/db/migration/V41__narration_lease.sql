-- Technical review 10/2026, Phase 1.6 (R-3): the AI call runs outside any database transaction. A job is claimed first
-- (status IN_PROGRESS + lease), the AI is called, then the result is stored. A lease that runs out (backend stopped
-- during the call) is claimed again. "attempts" exists since V2.
ALTER TABLE narration_job ADD COLUMN lease_until TIMESTAMP;
