-- Roadmap V3 R3-N1/N2: access from devices in the home network. One row (id 1) per installation - not per savegame.
-- The PIN is optional (owner decision) and only stored as PBKDF2WithHmacSHA256 hash with its salt.
CREATE TABLE lan_settings (
    id BIGINT PRIMARY KEY,
    enabled BOOLEAN DEFAULT FALSE NOT NULL,
    pin_hash VARCHAR(128),
    pin_salt VARCHAR(64),
    pin_iterations INT,
    updated_at TIMESTAMP NOT NULL
);
INSERT INTO lan_settings (id, enabled, updated_at) VALUES (1, FALSE, CURRENT_TIMESTAMP);
-- Sessions of devices that logged in with the PIN: only the SHA-256 of the cookie value is stored.
CREATE TABLE lan_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    remote_address VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
