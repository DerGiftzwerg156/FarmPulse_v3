-- TODO T-20: livestock trader offers (quantity, direction, head count when accepted).
ALTER TABLE service_case ADD COLUMN quantity INT;
ALTER TABLE service_case ADD COLUMN direction VARCHAR(8);
ALTER TABLE service_case ADD COLUMN baseline_count INT;
