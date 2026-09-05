ALTER TABLE ai_settings ADD COLUMN IF NOT EXISTS daily_token_budget BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_settings ADD COLUMN IF NOT EXISTS user_daily_token_budget BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS ai_usage_daily (
    usage_date DATE NOT NULL,
    actor VARCHAR(190) NOT NULL,
    model VARCHAR(128) NOT NULL,
    requests INT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (usage_date, actor, model)
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_date ON ai_usage_daily(usage_date);
