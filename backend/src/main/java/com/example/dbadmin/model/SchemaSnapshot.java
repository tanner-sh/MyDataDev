package com.example.dbadmin.model;

import java.time.Instant;

/**
 * 一个 Schema 在某一刻的结构。
 *
 * @param content    按表名排序的 ObjectDetail 列表（JSON）。存内容而不是存差异，是因为「和上周比」
 *                   这个问题事先不知道要和谁比
 * @param lastSeenAt 最后一次采集到这个结构的时间。再次采集得到同样的校验和时只推进这一列 ——
 *                   时间线上留下的应该是「结构变了」的时刻，而不是「我们检查过」的时刻
 */
public record SchemaSnapshot(
        long id,
        long connectionId,
        String schemaName,
        String label,
        int tableCount,
        String checksumSha256,
        String content,
        String capturedBy,
        Instant capturedAt,
        Instant lastSeenAt
) {
    /** 结构没变、只是又见到了一次。 */
    public SchemaSnapshot withLastSeenAt(Instant seenAt) {
        return new SchemaSnapshot(id, connectionId, schemaName, label, tableCount, checksumSha256, content,
                capturedBy, capturedAt, seenAt);
    }
}
