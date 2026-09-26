-- TODO T-11: farmlands hidden in the vanilla farmland menu are never traded by the tool.
ALTER TABLE farmland_ownership ADD COLUMN tradeable BOOLEAN DEFAULT TRUE NOT NULL;
