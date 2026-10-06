-- Roadmap V3.1 section D "Dorfleben".
-- R31-D: burdening events switched per savegame (night work and diesel theft on, crop damage off by default).
ALTER TABLE savegame ADD COLUMN burden_night_work BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE savegame ADD COLUMN burden_crop_damage BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN burden_diesel_theft BOOLEAN DEFAULT TRUE NOT NULL;

-- Narration jobs whose text goes into a newspaper article or a chat message instead of a mail.
ALTER TABLE narration_job ADD COLUMN target_type VARCHAR(32);
ALTER TABLE narration_job ADD COLUMN target_id BIGINT;

-- R31-D1: village newspaper, its articles and the news items the services drop for it.
CREATE TABLE newspaper_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    issue_number INT NOT NULL,
    month_index BIGINT NOT NULL,
    mid_month BOOLEAN DEFAULT FALSE NOT NULL,
    period INT,
    crop_year INT,
    from_game_time BIGINT NOT NULL,
    published_game_time BIGINT NOT NULL,
    headline VARCHAR(255)
);
CREATE INDEX ix_newspaper_issue_savegame ON newspaper_issue(savegame_id, month_index);

CREATE TABLE newspaper_article (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    issue_id BIGINT NOT NULL,
    section VARCHAR(32) NOT NULL,
    position INT NOT NULL,
    headline VARCHAR(255),
    body CLOB,
    facts_json CLOB,
    fallback BOOLEAN DEFAULT FALSE NOT NULL
);
CREATE INDEX ix_newspaper_article_issue ON newspaper_article(issue_id);

CREATE TABLE village_news (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    section VARCHAR(32) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    text VARCHAR(500) NOT NULL,
    game_time BIGINT NOT NULL
);
CREATE INDEX ix_village_news_savegame ON village_news(savegame_id, game_time);

-- R31-D2: village group chat.
CREATE TABLE chat_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    group_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    last_player_post_game_time BIGINT,
    CONSTRAINT uq_chat_group UNIQUE (savegame_id, group_key)
);

CREATE TABLE chat_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    group_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES game_character(id),
    CONSTRAINT uq_chat_member UNIQUE (group_id, character_id)
);

CREATE TABLE chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    group_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES game_character(id),
    kind VARCHAR(32) NOT NULL,
    topic VARCHAR(64),
    text CLOB,
    tone VARCHAR(16),
    link VARCHAR(255),
    related_case_id BIGINT,
    game_time BIGINT NOT NULL,
    pending BOOLEAN DEFAULT FALSE NOT NULL
);
CREATE INDEX ix_chat_message_group ON chat_message(group_id, game_time);
ALTER TABLE savegame ADD COLUMN chat_posts_day BIGINT;
ALTER TABLE savegame ADD COLUMN chat_posts_count INT DEFAULT 0 NOT NULL;

-- R31-D3: regulars' table.
ALTER TABLE savegame ADD COLUMN stammtisch_next_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN stammtisch_missed INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN stammtisch_loner_sum DOUBLE PRECISION DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN stammtisch_rumor_bonus BOOLEAN DEFAULT FALSE NOT NULL;

-- R31-D4: night work of the helpers (night milliseconds per sample) and the complaints.
CREATE TABLE night_work_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    game_time BIGINT NOT NULL,
    night_ms BIGINT NOT NULL
);
CREATE INDEX ix_night_work_sample ON night_work_sample(savegame_id, game_time);
ALTER TABLE savegame ADD COLUMN night_last_facts_game_time BIGINT;
ALTER TABLE savegame ADD COLUMN night_counted_from BIGINT;
ALTER TABLE savegame ADD COLUMN last_night_complaint_game_time BIGINT;

-- R31-D5: crop damage - samples in a row per vehicle and the complaints per owner.
CREATE TABLE crop_damage_streak (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    vehicle_id VARCHAR(64) NOT NULL,
    farmland_id INT NOT NULL,
    samples INT NOT NULL,
    last_game_time BIGINT NOT NULL,
    reported BOOLEAN DEFAULT FALSE NOT NULL,
    incident_id BIGINT
);
CREATE TABLE crop_damage_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT NOT NULL REFERENCES game_character(id),
    farmland_id INT NOT NULL,
    samples INT NOT NULL,
    game_time BIGINT NOT NULL,
    claim_case_id BIGINT
);
ALTER TABLE savegame ADD COLUMN crop_damage_hint_sent BOOLEAN DEFAULT FALSE NOT NULL;

-- R31-D6: farm holidays (since, last organic spreading for the smell complaint) and the monthly results.
ALTER TABLE savegame ADD COLUMN farm_holiday_since BIGINT;
ALTER TABLE savegame ADD COLUMN last_organic_spread_game_time BIGINT;
CREATE TABLE farm_holiday_month (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    month_index BIGINT NOT NULL,
    period INT NOT NULL,
    income BIGINT NOT NULL,
    season_factor DOUBLE PRECISION NOT NULL,
    reputation_factor DOUBLE PRECISION NOT NULL,
    animal_factor DOUBLE PRECISION NOT NULL,
    noise BOOLEAN DEFAULT FALSE NOT NULL,
    smell BOOLEAN DEFAULT FALSE NOT NULL,
    bad_review BOOLEAN DEFAULT FALSE NOT NULL,
    game_time BIGINT NOT NULL
);

-- R31-D7: cooperative shares, notices, prices for the dividend, assembly effects and the board.
ALTER TABLE savegame ADD COLUMN coop_shares INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN coop_board BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE savegame ADD COLUMN coop_board_missed INT DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN coop_dividend_bonus DOUBLE PRECISION DEFAULT 0 NOT NULL;
ALTER TABLE savegame ADD COLUMN coop_grain_store BOOLEAN DEFAULT FALSE NOT NULL;
CREATE TABLE coop_share_notice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    shares INT NOT NULL,
    noticed_game_time BIGINT NOT NULL,
    due_game_time BIGINT NOT NULL,
    paid_game_time BIGINT
);
CREATE TABLE coop_price_year (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    crop_year INT NOT NULL,
    fill_type VARCHAR(64) NOT NULL,
    price_sum DOUBLE PRECISION NOT NULL,
    samples INT NOT NULL,
    last_day BIGINT NOT NULL,
    CONSTRAINT uq_coop_price_year UNIQUE (savegame_id, crop_year, fill_type)
);

-- R31-D8: diesel theft, tank locks and the theft module of the storm / hail insurance.
CREATE TABLE diesel_theft (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    vehicle_id VARCHAR(64) NOT NULL,
    vehicle_name VARCHAR(255),
    liters BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT DEFAULT 0 NOT NULL,
    planned_game_time BIGINT NOT NULL,
    instruction_id VARCHAR(64),
    stolen_liters BIGINT,
    damage BIGINT,
    insurance_payout BIGINT,
    closed_game_time BIGINT
);
CREATE TABLE tank_lock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    vehicle_id VARCHAR(64) NOT NULL,
    bought_game_time BIGINT NOT NULL,
    CONSTRAINT uq_tank_lock UNIQUE (savegame_id, vehicle_id)
);
ALTER TABLE contract ADD COLUMN theft_cover BOOLEAN DEFAULT FALSE NOT NULL;
