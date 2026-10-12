-- Roadmap V3.3 R33-E1 (owner decision 2026-10-09): the period of the field book evaluation is kept per savegame.
-- null = not chosen (rpsim.formulas.field-book.default-years), 0 = all years, n = the last n harvest years.
ALTER TABLE savegame ADD COLUMN field_book_years INT;
