package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.TableRelation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/** 表关系的确认 / 否决记录，按连接归属（见 V29__TableRelation）。 */
@Repository
public class TableRelationRepository {
    private final JdbcTemplate jdbc;

    public TableRelationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TableRelation> list(long connectionId) {
        return jdbc.query("SELECT left_table, left_column, right_table, right_column, status, source, decided_by, decided_at"
                        + " FROM table_relation WHERE connection_id = ? ORDER BY decided_at DESC, id DESC",
                (rs, rowNum) -> {
                    Timestamp decidedAt = rs.getTimestamp("decided_at");
                    return new TableRelation(rs.getString("left_table"), rs.getString("left_column"),
                            rs.getString("right_table"), rs.getString("right_column"),
                            rs.getString("status"), rs.getString("source"), rs.getString("decided_by"),
                            decidedAt == null ? null : decidedAt.toInstant().toString());
                }, connectionId);
    }

    public int count(long connectionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM table_relation WHERE connection_id = ?", Integer.class, connectionId);
        return count == null ? 0 : count;
    }

    /** 同一对关联只占一行：已有就改判断，没有就新增。 */
    @Transactional
    public void upsert(long connectionId, String key, TableRelation relation) {
        int updated = jdbc.update("UPDATE table_relation SET status = ?, source = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP,"
                        + " left_table = ?, left_column = ?, right_table = ?, right_column = ? WHERE connection_id = ? AND relation_key = ?",
                relation.status(), relation.source(), relation.decidedBy(),
                relation.leftTable(), relation.leftColumn(), relation.rightTable(), relation.rightColumn(), connectionId, key);
        if (updated > 0) return;
        jdbc.update("INSERT INTO table_relation(connection_id, relation_key, left_table, left_column, right_table, right_column, status, source, decided_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                connectionId, key, relation.leftTable(), relation.leftColumn(), relation.rightTable(), relation.rightColumn(),
                relation.status(), relation.source(), relation.decidedBy());
    }

    public boolean delete(long connectionId, String key) {
        return jdbc.update("DELETE FROM table_relation WHERE connection_id = ? AND relation_key = ?", connectionId, key) > 0;
    }
}
