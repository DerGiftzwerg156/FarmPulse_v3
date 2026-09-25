-- Domain model (AP-3.2), mirrors technical concept chapter "Spring-Boot-Domänenmodell".

CREATE TABLE savegame (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bridge_savegame_id VARCHAR(255) UNIQUE,
    status VARCHAR(32) NOT NULL,
    map_name VARCHAR(255),
    current_game_time BIGINT NOT NULL,
    tone_preset VARCHAR(32) NOT NULL,
    farm_origin VARCHAR(32),
    village_relation VARCHAR(32),
    backstory_free_text CLOB,
    free_text_rejected BOOLEAN NOT NULL,
    starting_capital_target BIGINT NOT NULL,
    starting_capital_adjusted BOOLEAN NOT NULL,
    legacy_loan_amount BIGINT,
    initial_employees_json CLOB,
    dynamic_rotations_this_year INT NOT NULL,
    rotation_year_index INT NOT NULL,
    last_processed_game_day BIGINT,
    first_game_time BIGINT,
    market_context_json CLOB,
    generation_seed BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    linked_at TIMESTAMP
);

CREATE TABLE game_character (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    role VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    termination_reason VARCHAR(32),
    trust_score DOUBLE PRECISION NOT NULL,
    name VARCHAR(255) NOT NULL,
    traits VARCHAR(1000),
    speech_style VARCHAR(500),
    backstory CLOB,
    short_description VARCHAR(1000),
    relationships VARCHAR(1000),
    negotiation_trait VARCHAR(32) NOT NULL,
    virtual_wealth DOUBLE PRECISION NOT NULL,
    sell_willing BOOLEAN NOT NULL,
    generation_seed BIGINT NOT NULL,
    substitute_for_id BIGINT,
    absence_variant VARCHAR(32),
    on_leave_until_game_time BIGINT,
    last_trust_event_game_time BIGINT,
    last_paced_message_game_time BIGINT,
    joined_at_game_time BIGINT,
    left_at_game_time BIGINT,
    ai_enriched BOOLEAN NOT NULL
);
CREATE INDEX ix_game_character_0 ON game_character (savegame_id, status);
CREATE INDEX ix_game_character_sg ON game_character (savegame_id);

CREATE TABLE trust_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT NOT NULL REFERENCES game_character(id),
    game_time BIGINT NOT NULL,
    delta DOUBLE PRECISION NOT NULL,
    reason VARCHAR(32) NOT NULL,
    note VARCHAR(500)
);
CREATE INDEX ix_trust_event_0 ON trust_event (character_id);
CREATE INDEX ix_trust_event_sg ON trust_event (savegame_id);

CREATE TABLE loan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    application_id BIGINT,
    principal BIGINT NOT NULL,
    remaining_amount BIGINT NOT NULL,
    interest_rate DOUBLE PRECISION NOT NULL,
    term_months INT NOT NULL,
    monthly_installment BIGINT NOT NULL,
    purpose VARCHAR(500),
    status VARCHAR(32) NOT NULL,
    legacy BOOLEAN NOT NULL,
    blocks_new_credit BOOLEAN NOT NULL,
    started_at_game_time BIGINT NOT NULL,
    next_due_game_time BIGINT NOT NULL,
    overdue_since_game_time BIGINT,
    escalation_level INT NOT NULL,
    missed_installments INT NOT NULL,
    paid_installments INT NOT NULL,
    deferrals_used INT NOT NULL,
    deferred_until_game_time BIGINT
);
CREATE INDEX ix_loan_sg ON loan (savegame_id);

CREATE TABLE loan_payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    loan_id BIGINT NOT NULL REFERENCES loan(id),
    game_time BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    instruction_id VARCHAR(64)
);
CREATE INDEX ix_loan_payment_sg ON loan_payment (savegame_id);

CREATE TABLE credit_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    amount BIGINT NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    term_months INT NOT NULL,
    submitted_at_game_time BIGINT NOT NULL,
    decision_visible_at_game_time BIGINT NOT NULL,
    final_score DOUBLE PRECISION NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason_category VARCHAR(40) NOT NULL,
    offered_amount BIGINT,
    offered_term_months INT,
    offered_interest_rate DOUBLE PRECISION,
    status VARCHAR(32) NOT NULL,
    loan_id BIGINT,
    narrated BOOLEAN NOT NULL
);
CREATE INDEX ix_credit_application_sg ON credit_application (savegame_id);

CREATE TABLE market_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    fill_type VARCHAR(64),
    sell_point VARCHAR(255),
    peak_multiplier DOUBLE PRECISION,
    ramp_up_hours DOUBLE PRECISION,
    hold_hours DOUBLE PRECISION,
    decay_hours DOUBLE PRECISION,
    fixed_price BIGINT,
    max_quantity BIGINT,
    deadline_game_time BIGINT,
    subsidy_amount BIGINT,
    start_game_time BIGINT NOT NULL,
    end_game_time BIGINT,
    is_accurate BOOLEAN,
    referenced_event_id BIGINT,
    character_id BIGINT REFERENCES game_character(id),
    instruction_id VARCHAR(64),
    delivered_quantity BIGINT,
    end_reason VARCHAR(64),
    player_participation BOOLEAN,
    announced_at_game_time BIGINT NOT NULL
);
CREATE INDEX ix_market_event_0 ON market_event (savegame_id, status);
CREATE INDEX ix_market_event_sg ON market_event (savegame_id);

CREATE TABLE farmland_ownership (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    farmland_id INT NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    owner_character_id BIGINT REFERENCES game_character(id),
    reference_price BIGINT NOT NULL,
    hectares DOUBLE PRECISION NOT NULL,
    updated_at_game_time BIGINT NOT NULL
);
CREATE INDEX ix_farmland_ownership_0 ON farmland_ownership (savegame_id, farmland_id);
CREATE INDEX ix_farmland_ownership_sg ON farmland_ownership (savegame_id);

CREATE TABLE negotiation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    asset_type VARCHAR(32) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    direction VARCHAR(32) NOT NULL,
    initiated_by VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    counterpart_character_id BIGINT REFERENCES game_character(id),
    announcing_character_id BIGINT REFERENCES game_character(id),
    winner_character_id BIGINT REFERENCES game_character(id),
    base_price BIGINT NOT NULL,
    asking_price BIGINT,
    rounds_used INT NOT NULL,
    max_rounds INT NOT NULL,
    last_counter_offer BIGINT,
    final_price BIGINT,
    opened_at_game_time BIGINT NOT NULL,
    closes_at_game_time BIGINT,
    closed_at_game_time BIGINT,
    sale_group_id VARCHAR(64)
);
CREATE INDEX ix_negotiation_0 ON negotiation (savegame_id, asset_id, status);
CREATE INDEX ix_negotiation_sg ON negotiation (savegame_id);

CREATE TABLE negotiation_offer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    negotiation_id BIGINT NOT NULL REFERENCES negotiation(id),
    round_number INT NOT NULL,
    offered_by VARCHAR(32) NOT NULL,
    character_id BIGINT REFERENCES game_character(id),
    amount BIGINT NOT NULL,
    result VARCHAR(32) NOT NULL,
    counter_amount BIGINT,
    hidden_max_bid BIGINT,
    game_time BIGINT NOT NULL
);
CREATE INDEX ix_negotiation_offer_sg ON negotiation_offer (savegame_id);

CREATE TABLE employee (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT NOT NULL REFERENCES game_character(id),
    job_role VARCHAR(32) NOT NULL,
    skill INT NOT NULL,
    monthly_salary BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    hired_at_game_time BIGINT NOT NULL,
    terminated_at_game_time BIGINT,
    pay_fairness DOUBLE PRECISION NOT NULL,
    workload DOUBLE PRECISION NOT NULL,
    appreciation DOUBLE PRECISION NOT NULL,
    needs_updated_at_game_time BIGINT NOT NULL,
    low_satisfaction_since_game_time BIGINT,
    warning_sent BOOLEAN NOT NULL,
    salary_overdue BOOLEAN NOT NULL,
    last_conversation_game_time BIGINT,
    last_effect_multiplier DOUBLE PRECISION NOT NULL,
    time_off_until_game_time BIGINT,
    next_salary_due_game_time BIGINT NOT NULL
);
CREATE INDEX ix_employee_sg ON employee (savegame_id);

CREATE TABLE satisfaction_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    employee_id BIGINT NOT NULL REFERENCES employee(id),
    game_time BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    delta DOUBLE PRECISION NOT NULL,
    reason VARCHAR(255)
);
CREATE INDEX ix_satisfaction_event_sg ON satisfaction_event (savegame_id);

CREATE TABLE job_posting (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    job_role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at_game_time BIGINT NOT NULL,
    filled_employee_id BIGINT
);
CREATE INDEX ix_job_posting_sg ON job_posting (savegame_id);

CREATE TABLE job_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    posting_id BIGINT NOT NULL REFERENCES job_posting(id),
    character_id BIGINT NOT NULL REFERENCES game_character(id),
    skill INT NOT NULL,
    expected_salary BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at_game_time BIGINT NOT NULL
);
CREATE INDEX ix_job_application_sg ON job_application (savegame_id);

CREATE TABLE communication (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT REFERENCES game_character(id),
    channel VARCHAR(16) NOT NULL,
    initiated_by VARCHAR(16) NOT NULL,
    subject VARCHAR(500),
    body CLOB,
    game_time BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_flag BOOLEAN NOT NULL,
    category VARCHAR(32) NOT NULL,
    event_type VARCHAR(64),
    related_entity_type VARCHAR(64),
    related_entity_id BIGINT,
    thread_root_id BIGINT,
    call_status VARCHAR(16),
    ring_deadline_game_time BIGINT,
    open_topic BOOLEAN NOT NULL,
    used_fallback BOOLEAN NOT NULL,
    form_link VARCHAR(255),
    tone_class VARCHAR(16)
);
CREATE INDEX ix_communication_0 ON communication (savegame_id, channel);
CREATE INDEX ix_communication_sg ON communication (savegame_id);

CREATE TABLE outbox_instruction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    instruction_id VARCHAR(64) NOT NULL UNIQUE,
    batch_id VARCHAR(64),
    type VARCHAR(32) NOT NULL,
    payload_json CLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    game_time_earliest BIGINT,
    created_at_game_time BIGINT NOT NULL,
    acked_at_game_time BIGINT,
    ack_message VARCHAR(1000),
    related_entity_type VARCHAR(64),
    related_entity_id BIGINT,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_outbox_instruction_0 ON outbox_instruction (savegame_id, status);
CREATE INDEX ix_outbox_instruction_sg ON outbox_instruction (savegame_id);

CREATE TABLE facts_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    game_time BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL,
    balance BIGINT NOT NULL,
    raw_json CLOB NOT NULL
);
CREATE INDEX ix_facts_snapshot_0 ON facts_snapshot (savegame_id, game_time);
CREATE INDEX ix_facts_snapshot_sg ON facts_snapshot (savegame_id);

CREATE TABLE story_hook (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    hook_key VARCHAR(64) NOT NULL,
    title VARCHAR(255),
    description VARCHAR(2000),
    character_id BIGINT REFERENCES game_character(id),
    scheduled_game_time BIGINT NOT NULL,
    fired BOOLEAN NOT NULL,
    fired_at_game_time BIGINT
);
CREATE INDEX ix_story_hook_sg ON story_hook (savegame_id);

CREATE TABLE public_action_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    game_time BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    delta DOUBLE PRECISION NOT NULL,
    note VARCHAR(500)
);
CREATE INDEX ix_public_action_event_sg ON public_action_event (savegame_id);

CREATE TABLE diary_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    game_time BIGINT NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    category VARCHAR(64),
    title VARCHAR(255) NOT NULL,
    text CLOB,
    created_at TIMESTAMP NOT NULL,
    related_entity_type VARCHAR(64),
    related_entity_id BIGINT
);
CREATE INDEX ix_diary_entry_sg ON diary_entry (savegame_id);

CREATE TABLE narration_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    savegame_id BIGINT NOT NULL REFERENCES savegame(id),
    character_id BIGINT REFERENCES game_character(id),
    event_type VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    category VARCHAR(32) NOT NULL,
    facts_json CLOB NOT NULL,
    player_message CLOB,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    not_before_game_time BIGINT NOT NULL,
    related_entity_type VARCHAR(64),
    related_entity_id BIGINT,
    thread_root_id BIGINT,
    communication_id BIGINT,
    used_fallback BOOLEAN NOT NULL,
    form_link VARCHAR(255),
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_narration_job_0 ON narration_job (status, not_before_game_time);
CREATE INDEX ix_narration_job_sg ON narration_job (savegame_id);
