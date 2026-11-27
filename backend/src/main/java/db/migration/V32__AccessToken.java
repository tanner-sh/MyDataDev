package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 个人访问令牌（PAT）及连接白名单表结构：
 * <ul>
 *   <li>{@code app_access_token}：令牌元数据、SHA-256 哈希、签发时账号版本与身份来源、连接范围；</li>
 *   <li>{@code app_access_token_connection}：当 {@code connection_scope = 'LISTED'} 时的连接白名单。</li>
 * </ul>
 */
public class V32__AccessToken extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS app_access_token (
                        id VARCHAR(24) PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        name VARCHAR(120) NOT NULL,
                        secret_hash CHAR(64) NOT NULL,
                        issued_auth_version BIGINT NOT NULL,
                        issued_provider VARCHAR(40) NOT NULL,
                        connection_scope VARCHAR(20) NOT NULL,
                        expires_at TIMESTAMP NOT NULL,
                        last_used_at TIMESTAMP,
                        last_used_ip VARCHAR(64),
                        revoked_at TIMESTAMP,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_access_token_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_access_token_user ON app_access_token (user_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS app_access_token_connection (
                        token_id VARCHAR(24) NOT NULL,
                        connection_id BIGINT NOT NULL,
                        PRIMARY KEY (token_id, connection_id),
                        CONSTRAINT fk_access_token_conn_token FOREIGN KEY (token_id) REFERENCES app_access_token(id) ON DELETE CASCADE,
                        CONSTRAINT fk_access_token_conn_db FOREIGN KEY (connection_id) REFERENCES db_connection(id) ON DELETE CASCADE
                    )""");
        }
    }
}
