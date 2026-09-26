-- TODO.md T-02 / T-03: rewind detection (reload without saving), dashboard notices, reversible loan payments.
CREATE TABLE notice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    game_time BIGINT NOT NULL,
    details_json CLOB,
    related_entity_type VARCHAR(64),
    related_entity_id BIGINT,
    resolution VARCHAR(32),
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP
);
CREATE INDEX ix_notice_sg ON notice (savegame_id, status);

CREATE TABLE bridge_rewind (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    previous_game_time BIGINT NOT NULL,
    rewound_to_game_time BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    lost_instruction_ids CLOB,
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP
);
CREATE INDEX ix_bridge_rewind_sg ON bridge_rewind (savegame_id, status);

-- principal share of an installment, so a payment the mod could not execute can be reversed exactly
ALTER TABLE loan_payment ADD COLUMN principal_part BIGINT;
ALTER TABLE loan_payment ADD COLUMN trust_bonus_given BOOLEAN;
