CREATE TABLE IF NOT EXISTS ai_settings (
    id INT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    provider VARCHAR(32) NOT NULL,
    base_url VARCHAR(512),
    model VARCHAR(128) NOT NULL,
    api_key_cipher VARCHAR(2048),
    effort VARCHAR(16) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_settings_singleton CHECK (id = 1)
);

CREATE TABLE IF NOT EXISTS ai_connection_policy (
    connection_id BIGINT PRIMARY KEY,
    schema_sharing VARCHAR(24) NOT NULL DEFAULT 'NONE',
    sample_row_limit INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_policy_connection FOREIGN KEY (connection_id) REFERENCES db_connection(id) ON DELETE CASCADE
);
