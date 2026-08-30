-- P1：个人数据归属、OIDC 组同步和可验证审计链。
-- 所有 ALTER 都是增量操作；既有连接、凭据和业务数据不会被重建或改写。

ALTER TABLE sql_snippet ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'SHARED';
ALTER TABLE sql_snippet ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;
DROP INDEX IF EXISTS ux_sql_snippet_name;
CREATE INDEX IF NOT EXISTS idx_sql_snippet_owner_visibility ON sql_snippet(owner_user_id, visibility, id);

ALTER TABLE sql_history ADD COLUMN IF NOT EXISTS actor_user_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_sql_history_connection_actor ON sql_history(connection_id, actor_user_id, id);

ALTER TABLE app_user_group_member ADD COLUMN IF NOT EXISTS source_provider VARCHAR(40) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS previous_hash VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS event_hash VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_log_event_hash ON audit_log(event_hash);

CREATE TABLE IF NOT EXISTS audit_chain_state (
    id INT PRIMARY KEY,
    anchor_hash VARCHAR(64),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO audit_chain_state(id, anchor_hash)
SELECT 1, NULL WHERE NOT EXISTS (SELECT 1 FROM audit_chain_state WHERE id = 1);
