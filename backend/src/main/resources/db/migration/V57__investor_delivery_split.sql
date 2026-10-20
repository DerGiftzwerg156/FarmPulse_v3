-- Logic review LR-L15 (owner decision 2026-10-20): the periods a counted investor delivery went to ("periodId:quantity,
-- ..."), so a re-sent delivery the mod refuses after a rewind takes exactly these quantities back. Null before V57.
ALTER TABLE investor_delivery ADD COLUMN period_split VARCHAR(1000);
