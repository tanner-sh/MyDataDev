package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.Reader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 一次对比的两侧数据及生成脚本共用预算。字符串按每字符 4 字节计费，为读取缓冲和拷贝
 * 留余量，集合与行另外预留开销。JDBC 驱动可能自行缓存结果，此预算不等于进程堆硬上限。
 */
final class DataDiffBudget {
    private final long maximum;
    private final int maxCellChars;
    private final char[] buffer = new char[4096];
    private long used;

    DataDiffBudget(long maximum, int maxCellChars) {
        this.maximum = maximum;
        this.maxCellChars = maxCellChars;
    }

    void reserve(long bytes) {
        if (bytes < 0 || bytes > maximum - used) {
            throw new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_DIFF_MEMORY_LIMIT",
                    "数据对比超过单次内存估算预算（" + maximum + " 字节），已停止且未生成对比结果。"
                            + "请缩小对比数据，或由管理员调整 app.data-diff.max-estimated-bytes。");
        }
        used += bytes;
    }

    void retainText(String value) {
        reserve(64L + (value == null ? 0 : 4L * value.length()));
    }

    String readCell(ResultSet rs, int index, int jdbcType) throws SQLException, IOException {
        // 数值/时间转字符流并非每个驱动都支持；这些类型没有无界文本载荷，沿用 getString
        // 的格式，再经过同一长度检查。文本及其他可能很大的类型不退回无界 getString。
        return switch (jdbcType) {
            case Types.NULL, Types.BOOLEAN, Types.BIT, Types.TINYINT, Types.SMALLINT, Types.INTEGER,
                    Types.BIGINT, Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL,
                    Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP_WITH_TIMEZONE, Types.ROWID,
                    -101, -102, -103, -104 -> { // Oracle 带时区时间及 INTERVAL
                String value = rs.getString(index);
                requireCellLength(value == null ? 0 : value.length());
                retainText(value);
                yield value;
            }
            default -> readCell(rs.getCharacterStream(index));
        };
    }

    /** 在每次追加之前检查，避免先 getString 把整个超大字段装进应用内存。 */
    String readCell(Reader reader) throws IOException {
        try (reader) {
            reserve(64);
            if (reader == null) return null;
            StringBuilder value = new StringBuilder();
            int length;
            while ((length = reader.read(buffer, 0,
                    (int) Math.min(buffer.length, (long) maxCellChars - value.length() + 1))) != -1) {
                requireCellLength((long) value.length() + length);
                reserve(4L * length);
                value.append(buffer, 0, length);
            }
            return value.toString();
        }
    }

    private void requireCellLength(long length) {
        if (length > maxCellChars) {
            throw new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_DIFF_CELL_LIMIT",
                    "数据对比遇到超过 " + maxCellChars + " 字符的字段，已停止且未生成对比结果。"
                            + "请缩小字段内容，或由管理员调整 app.data-diff.max-cell-chars。");
        }
    }
}
