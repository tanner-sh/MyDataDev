package com.example.dbadmin.service.ai;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.ReadOnlyQueryScope;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlStatementClassifier;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.List;

/**
 * 在目标数据库上对候选 SQL 做只取结构、不取业务行的编译校验。
 *
 * <p>怎么校验交给 {@link DatabaseDialect#compileQuery}：驱动之间差别很大，PostgreSQL 的
 * prepare 是一次服务端 Describe，Connector/J 的同一个调用却会把查询真跑一遍。把这个判断留在
 * 方言里，这里只负责三道闸门 —— 一条语句、必须是 SELECT/WITH、只读回滚事务。</p>
 *
 * <p>收窄到 SELECT/WITH 是有意的：SHOW、DESCRIBE 与 EXPLAIN 也算只读，但
 * {@code EXPLAIN ANALYZE SELECT} 在 PostgreSQL 上是真执行，而 AI 反复调用这个入口，每一次
 * 误判的代价都直接落在目标库上。</p>
 */
@Service
public class AiSqlValidationService {
    private final ConnectionService connections;
    private final DialectRegistry dialects;
    private final SqlScriptSplitter splitter;
    private final SqlStatementClassifier classifier;
    private final int timeoutSeconds;

    public AiSqlValidationService(
            ConnectionService connections,
            DialectRegistry dialects,
            SqlScriptSplitter splitter,
            SqlStatementClassifier classifier,
            AppProperties properties
    ) {
        this.connections = connections;
        this.dialects = dialects;
        this.splitter = splitter;
        this.classifier = classifier;
        this.timeoutSeconds = Math.max(1, properties.getAiAgent().getValidationTimeoutSeconds());
    }

    public ValidationResult validate(long connectionId, String schemaName, String sql) {
        if (sql == null || sql.isBlank()) return ValidationResult.invalid("SQL 不能为空。");
        List<SqlScriptSplitter.StatementSegment> statements = splitter.split(sql);
        if (statements.size() != 1) return ValidationResult.invalid("只能校验一条 SQL 查询。");
        String statementSql = statements.get(0).sql();
        if (!classifier.isSelectQuery(statementSql)) {
            return ValidationResult.invalid("候选 SQL 不是一条 SELECT 查询（允许 WITH 前缀）。");
        }
        DatabaseDialect dialect = dialects.dialectFor(connections.require(connectionId));
        try (Connection connection = connections.open(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true)) {
            dialect.compileQuery(connection, statementSql, timeoutSeconds);
            return ValidationResult.valid("已通过目标数据库的编译校验（只解析计划，未取数据）。");
        } catch (Exception e) {
            return ValidationResult.invalid("目标数据库编译失败：" + safeMessage(e));
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.substring(0, Math.min(message.length(), 600));
    }

    public record ValidationResult(boolean valid, String message) {
        static ValidationResult valid(String message) { return new ValidationResult(true, message); }
        static ValidationResult invalid(String message) { return new ValidationResult(false, message); }
    }
}
