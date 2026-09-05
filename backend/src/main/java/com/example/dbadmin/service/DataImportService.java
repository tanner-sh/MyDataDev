package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse;
import com.example.dbadmin.model.DbConnection;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 大文件数据导入。
 *
 * <p>此前导入只有一条路径：浏览器把整份文件读进内存解析，10 MB / 1000 行封顶，再走和手工编辑
 * 同一个 /data/commit。而后端早就有一整套大文件后台执行框架（分块读取、进度上报、可取消、
 * 每连接并发闸门）—— 只是给 SQL 文件用的。</p>
 *
 * <p>这里把 CSV 流式转成批量 INSERT 脚本，交给同一条管线，于是百万行导入自然获得进度、取消
 * 与排队能力，而不需要新建一套后台任务。</p>
 */
@Service
public class DataImportService {
    /** 每条 INSERT 携带的行数：太小语句数爆炸，太大单条语句会超出驱动的报文上限。 */
    static final int ROWS_PER_STATEMENT = 200;
    static final int MAX_COLUMNS = 500;
    /**
     * CSV 转成 INSERT 之后的体积膨胀系数，只用于落盘前的剩余空间预检。
     *
     * <p>每 {@link #ROWS_PER_STATEMENT} 行重复一次列名和 INSERT 头，每个值还要加引号和逗号，
     * 所以脚本一定比源文件大。取 2 是个保守的经验值：宁可在磁盘将满时早一点拒绝，也不要写到
     * 一半才 ENOSPC —— 那时请求体已经收了一大半。</p>
     */
    static final int SCRIPT_SIZE_FACTOR = 2;

    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final DataEditService dataEdit;
    private final SqlFileExecutionService sqlFiles;
    private final ExecutionGuard executionGuard;

    public DataImportService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            DataEditService dataEdit,
            SqlFileExecutionService sqlFiles,
            ExecutionGuard executionGuard
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.dataEdit = dataEdit;
        this.sqlFiles = sqlFiles;
        this.executionGuard = executionGuard;
    }

    /**
     * 接收一份 CSV，转成 INSERT 脚本并注册成待执行的 SQL 文件任务。
     *
     * <p>返回的任务还需要调用方再调 start 才会真正写库 —— 与 SQL 文件上传的两步流程一致，
     * 用户有机会先看清楚要往哪张表写多少行、有没有危险语句。</p>
     *
     * @param contentLength 请求体声明的字节数，交给落盘前的剩余空间预检；未知传 0。
     */
    public SqlFileExecutionResponse uploadCsv(
            long connectionId,
            String schemaName,
            String tableName,
            String fileName,
            long contentLength,
            InputStream input,
            String actor
    ) throws Exception {
        return upload(connectionId, schemaName, tableName, fileName, contentLength, actor,
                () -> new CsvStreamReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
    }

    /**
     * 接收一份 Excel（.xlsx），按第一个工作表转成 INSERT 脚本。
     *
     * <p>与 CSV 走同一条管线，区别只在读行那一步。Excel 比 CSV 多一层麻烦：日期在文件里
     * 就是个数字，是不是日期只写在样式里 —— 那部分由 {@link XlsxStreamReader} 还原，
     * 这里拿到的已经是文本。</p>
     */
    public SqlFileExecutionResponse uploadXlsx(
            long connectionId,
            String schemaName,
            String tableName,
            String fileName,
            long contentLength,
            InputStream input,
            String actor
    ) throws Exception {
        return upload(connectionId, schemaName, tableName, fileName, contentLength, actor,
                () -> new XlsxStreamReader(input));
    }

    private SqlFileExecutionResponse upload(
            long connectionId,
            String schemaName,
            String tableName,
            String fileName,
            long contentLength,
            String actor,
            RowSourceFactory sourceFactory
    ) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        // 导入是写操作。只读连接在这里就该被挡住，而不是等脚本转换完、任务建好之后才报错。
        executionGuard.requireWritableConnection(dbConnection);
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("请指定导入的目标表。");
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);

        Set<String> tableColumns;
        try (Connection connection = connections.open(connectionId, schemaName)) {
            tableColumns = new LinkedHashSet<>(dataEdit.editableColumns(connection, dbConnection, schemaName, tableName));
        } catch (SQLException error) {
            // 目标表不存在是最常见的失败，驱动原文是英文且带 SQL 片段，对用户没有意义。
            throw new IllegalArgumentException(
                    "无法读取目标表 " + dialect.qualifiedName(schemaName, tableName) + " 的字段，请确认表名与所在 Schema 是否正确。", error
            );
        }
        if (tableColumns.isEmpty()) throw new IllegalArgumentException("未找到目标表的字段：" + tableName);

        return sqlFiles.uploadScript(
                connectionId,
                importScriptName(fileName, tableName),
                contentLength > 0 ? contentLength * SCRIPT_SIZE_FACTOR : 0,
                out -> {
                    try (ImportRowSource source = sourceFactory.open()) {
                        return "rows=" + convert(source, out, dialect, schemaName, tableName, tableColumns, fileName);
                    }
                },
                actor,
                "DATA_IMPORT_UPLOAD",
                "table=" + tableName + "; file=" + fileName
        );
    }

    static long convert(
            ImportRowSource source,
            Writer writer,
            DatabaseDialect dialect,
            String schemaName,
            String tableName,
            Set<String> tableColumns,
            String fileName
    ) throws Exception {
        String kind = source.label();
        List<String> header = source.readRow();
        if (header == null || header.isEmpty()) throw new IllegalArgumentException(kind + "文件为空或缺少表头行。");
        if (header.size() > MAX_COLUMNS) throw new IllegalArgumentException(kind + "列数超过 " + MAX_COLUMNS + "。");

        List<String> columns = new ArrayList<>(header.size());
        for (String raw : header) {
            String name = stripBom(raw).trim();
            String matched = tableColumns.stream()
                    .filter(candidate -> candidate.equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            kind + "表头里的列在目标表中不存在：" + name + "（表字段：" + String.join("、", tableColumns) + "）"
                    ));
            columns.add(matched);
        }

        String qualified = dialect.qualifiedName(schemaName, tableName);
        String columnList = String.join(", ", columns.stream().map(dialect::quoteIdentifier).toList());
        writer.write("-- 由 " + commentText(fileName) + " 转换而来的导入脚本\n");
        writer.write("-- 目标表：" + commentText(qualified) + "\n\n");

        long rows = 0;
        int inBatch = 0;
        List<String> row;
        while ((row = source.readRow()) != null) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException(
                        "第 " + (rows + 2) + " 行有 " + row.size() + " 个字段，与表头的 " + columns.size() + " 列不一致。"
                );
            }
            if (inBatch == 0) writer.write("INSERT INTO " + qualified + " (" + columnList + ") VALUES\n");
            else writer.write(",\n");
            writer.write("  (");
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) writer.write(", ");
                writer.write(literal(dialect, row.get(index)));
            }
            writer.write(")");
            rows++;
            inBatch++;
            if (inBatch >= ROWS_PER_STATEMENT) {
                writer.write(";\n\n");
                inBatch = 0;
            }
        }
        if (inBatch > 0) writer.write(";\n");
        if (rows == 0) throw new IllegalArgumentException(kind + "文件只有表头，没有数据行。");
        return rows;
    }

    /** 行来源的构造时机要推迟到真正开始落盘时 —— 上传流只能读一次。 */
    private interface RowSourceFactory {
        ImportRowSource open() throws Exception;
    }

    /**
     * 空字段写成 NULL；其余一律按字符串字面量，由数据库按目标列类型隐式转换。
     *
     * <p>不做类型推断：CSV 里没有类型信息，猜错的代价（把 "007" 变成 7、把 "1e5" 变成浮点）
     * 比多一次隐式转换大得多。</p>
     *
     * <p>转义交给方言，不能在这里自己拼：MySQL、ClickHouse 默认还认反斜杠转义，只翻倍单引号
     * 的话，一个以反斜杠结尾的单元格就能让字符串提前结束，后面的内容变成可执行的 SQL。用
     * scriptLiteral 而不是 literal —— 这份脚本是先生成、后执行的，写法不能依赖生成时的会话
     * 设置（MySQL 的 NO_BACKSLASH_ESCAPES 会改变反斜杠的含义）。</p>
     */
    static String literal(DatabaseDialect dialect, String value) {
        if (value == null || value.isEmpty()) return "NULL";
        return dialect.scriptLiteral(value);
    }

    /**
     * 写进 {@code --} 注释的文本必须是单行的。
     *
     * <p>文件名由请求参数带进来，换行没被去掉的话注释就在那里结束了，后面的内容会被脚本执行
     * 管线当成语句执行 —— 一条注释就成了注入点。</p>
     */
    static String commentText(String value) {
        if (value == null) return "";
        String single = value.replaceAll("\\R", " ").replace("*/", "* /");
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }

    /** Excel 导出的 CSV 常带 UTF-8 BOM，不剥掉会让第一列名匹配不上。 */
    static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '﻿' ? value.substring(1) : value;
    }

    /** 生成的脚本名要以 .sql 结尾（后台管线的硬性要求），同时保留原始文件名便于在任务列表里辨认。 */
    static String importScriptName(String fileName, String tableName) {
        String base = fileName == null || fileName.isBlank() ? "import" : fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String name = (base + "-导入-" + tableName + ".sql").replaceAll("[\\\\/:*?\"<>|\\x00]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
