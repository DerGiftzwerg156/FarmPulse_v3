-- Roadmap vehicle dealer RFH-Q1 (owner decision 2026-10-15): the raw export/store_catalog.json of the linked savegame
-- (shop catalog with every configuration option, written by the mod once per mission start), like market_context_json.
ALTER TABLE savegame ADD COLUMN store_catalog_json CLOB;
