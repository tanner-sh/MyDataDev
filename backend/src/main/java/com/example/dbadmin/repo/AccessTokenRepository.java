package com.example.dbadmin.repo;

import com.example.dbadmin.auth.AccessToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class AccessTokenRepository {
    private static final String COLUMNS = """
            id, user_id, name, secret_hash, issued_auth_version, issued_provider,
            connection_scope, expires_at, last_used_at, last_used_ip, revoked_at, created_at
            """;

    private final JdbcTemplate jdbc;

    public AccessTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void insert(
            String id,
            long userId,
            String name,
            String secretHash,
            long issuedAuthVersion,
            String issuedProvider,
            String connectionScope,
            Collection<Long> connectionIds,
            Instant expiresAt
    ) {
        jdbc.update("""
                INSERT INTO app_access_token(
                    id, user_id, name, secret_hash, issued_auth_version, issued_provider,
                    connection_scope, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, name, secretHash, issuedAuthVersion, issuedProvider,
                connectionScope, Timestamp.from(expiresAt));
        replaceConnections(id, connectionScope, connectionIds);
    }

    public Optional<AccessToken> findById(String id) {
        List<TokenRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM app_access_token WHERE id = ?",
                AccessTokenRepository::mapRow,
                id
        );
        if (rows.isEmpty()) return Optional.empty();
        Set<Long> connections = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT connection_id FROM app_access_token_connection WHERE token_id = ? ORDER BY connection_id",
                Long.class,
                id
        ));
        return Optional.of(toToken(rows.get(0), connections));
    }

    public List<AccessToken> findByUserId(long userId) {
        List<TokenRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM app_access_token WHERE user_id = ? ORDER BY created_at DESC, id DESC",
                AccessTokenRepository::mapRow,
                userId
        );
        return attachConnections(rows);
    }

    public List<AccessToken> findAll() {
        List<TokenRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM app_access_token ORDER BY created_at DESC, id DESC",
                AccessTokenRepository::mapRow
        );
        return attachConnections(rows);
    }

    @Transactional
    public void updateConnections(String tokenId, String connectionScope, Collection<Long> connectionIds) {
        int changed = jdbc.update(
                "UPDATE app_access_token SET connection_scope = ? WHERE id = ? AND revoked_at IS NULL",
                connectionScope,
                tokenId
        );
        if (changed == 0) throw new IllegalArgumentException("访问令牌不存在或已吊销。");
        replaceConnections(tokenId, connectionScope, connectionIds);
    }

    public boolean revoke(String tokenId, Instant revokedAt) {
        return jdbc.update(
                "UPDATE app_access_token SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(revokedAt),
                tokenId
        ) > 0;
    }

    public void touchLastUsed(String tokenId, Instant usedAt, String ip) {
        jdbc.update(
                "UPDATE app_access_token SET last_used_at = ?, last_used_ip = ? WHERE id = ?",
                Timestamp.from(usedAt),
                truncate(ip, 64),
                tokenId
        );
    }

    private void replaceConnections(String tokenId, String connectionScope, Collection<Long> connectionIds) {
        jdbc.update("DELETE FROM app_access_token_connection WHERE token_id = ?", tokenId);
        if (!AccessToken.SCOPE_LISTED.equals(connectionScope) || connectionIds == null) return;
        for (Long connectionId : new LinkedHashSet<>(connectionIds)) {
            if (connectionId == null) continue;
            jdbc.update(
                    "INSERT INTO app_access_token_connection(token_id, connection_id) VALUES (?, ?)",
                    tokenId,
                    connectionId
            );
        }
    }

    private List<AccessToken> attachConnections(List<TokenRow> rows) {
        if (rows.isEmpty()) return List.of();
        Map<String, Set<Long>> byToken = new LinkedHashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(TokenRow::id).toArray();
        jdbc.query(
                "SELECT token_id, connection_id FROM app_access_token_connection WHERE token_id IN ("
                        + placeholders + ") ORDER BY token_id, connection_id",
                rs -> {
                    byToken.computeIfAbsent(rs.getString("token_id"), ignored -> new LinkedHashSet<>())
                            .add(rs.getLong("connection_id"));
                },
                ids
        );
        return rows.stream()
                .map(row -> toToken(row, byToken.getOrDefault(row.id(), Set.of())))
                .toList();
    }

    private static AccessToken toToken(TokenRow row, Set<Long> connectionIds) {
        return new AccessToken(
                row.id(),
                row.userId(),
                row.name(),
                row.secretHash(),
                row.issuedAuthVersion(),
                row.issuedProvider(),
                row.connectionScope(),
                Set.copyOf(connectionIds),
                row.expiresAt(),
                row.lastUsedAt(),
                row.lastUsedIp(),
                row.revokedAt(),
                row.createdAt()
        );
    }

    private static TokenRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TokenRow(
                rs.getString("id"),
                rs.getLong("user_id"),
                rs.getString("name"),
                rs.getString("secret_hash"),
                rs.getLong("issued_auth_version"),
                rs.getString("issued_provider"),
                rs.getString("connection_scope"),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("last_used_at")),
                rs.getString("last_used_ip"),
                instant(rs.getTimestamp("revoked_at")),
                instant(rs.getTimestamp("created_at"))
        );
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private record TokenRow(
            String id,
            long userId,
            String name,
            String secretHash,
            long issuedAuthVersion,
            String issuedProvider,
            String connectionScope,
            Instant expiresAt,
            Instant lastUsedAt,
            String lastUsedIp,
            Instant revokedAt,
            Instant createdAt
    ) {}
}
