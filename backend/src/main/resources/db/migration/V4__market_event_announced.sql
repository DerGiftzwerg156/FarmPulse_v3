-- AP-4.3: events that are only known via a rumour are announced when they start.
ALTER TABLE market_event ADD COLUMN announced BOOLEAN DEFAULT FALSE NOT NULL;
