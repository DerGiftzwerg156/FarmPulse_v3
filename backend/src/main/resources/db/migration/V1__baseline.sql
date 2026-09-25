-- Baseline migration (AP-3.1). Domain tables follow in V2 (AP-3.2).
CREATE TABLE app_meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255));
INSERT INTO app_meta (meta_key, meta_value) VALUES ('schema', 'rpsim');
