-- Roadmap V2 R2-F: decisions directly in the game.
-- F2: every yes/no question sent to the mod (PROMPT); the key identifies the occasion so it is asked only once.
CREATE TABLE game_prompt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    prompt_id VARCHAR(64) NOT NULL UNIQUE,
    prompt_key VARCHAR(96) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    instruction_id VARCHAR(64),
    title VARCHAR(120) NOT NULL,
    text VARCHAR(600) NOT NULL,
    created_game_time BIGINT NOT NULL,
    expires_game_time BIGINT NOT NULL,
    answer VARCHAR(32),
    answered_game_time BIGINT,
    result VARCHAR(255),
    CONSTRAINT uq_game_prompt_key UNIQUE (savegame_id, prompt_key)
);
CREATE INDEX ix_game_prompt_status ON game_prompt (savegame_id, status);

-- F1: answers read from export/player_responses.json (idempotent by responseId).
CREATE TABLE player_response (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    response_id VARCHAR(96) NOT NULL,
    prompt_id VARCHAR(64) NOT NULL,
    answer VARCHAR(32) NOT NULL,
    game_time BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_player_response UNIQUE (savegame_id, response_id)
);

-- F2: occasions asked in the game, comma separated PromptKind names; NULL = rpsim.bridge.prompt-default-kinds.
ALTER TABLE savegame ADD COLUMN ingame_prompt_kinds VARCHAR(255);
