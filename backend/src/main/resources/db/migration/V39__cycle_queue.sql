-- Technical review 10/2026, Phase 1.2/1.3 (R-1): durable work queue of the bridge cycle and the journal of its steps.
-- cycle_event: what a bridge read decided must still happen, in order (stored in the same transaction as the read).
-- cycle_step: one row per (queued event, sub step, listener) - DONE is written in the same transaction as the
-- listener's own changes, so a retried cycle never runs a listener twice.
CREATE TABLE cycle_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    event_type VARCHAR(200) NOT NULL,
    payload_json CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_cycle_event_sg ON cycle_event (savegame_id, id);

CREATE TABLE cycle_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    cycle_event_id BIGINT NOT NULL,
    step_key VARCHAR(100) NOT NULL,
    listener_id VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    last_error VARCHAR(2000),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_cycle_step UNIQUE (cycle_event_id, step_key, listener_id)
);
