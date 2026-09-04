package com.example.dbadmin.service.ai;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.ReadOnlyQueryScope;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlStatementClassifier;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

/**
 * 在目标数据库上对候选 SQL 做只读、零行编译校验。
 *
 * <p>这里不会调用 {@code execute}：只创建 PreparedStatement 并读取结果列元数据。即便驱动忽略
 * JDBC 的 readOnly 提示，SQL 分类器和回滚事务也会在它接触数据库之前拦住写入语句。</p>
 */
@Service
public class AiSqlValidationService {
    private final ConnectionService connections;
    private final SqlScriptSplitter splitter;
    private final SqlStatementClassifier classifier;
    private final int timeoutSeconds;

    public AiSqlValidationService(
            ConnectionService connections,
            SqlScriptSplitter splitter,
            SqlStatementClassifier classifier,
            AppProperties properties
    ) {
        this.connections = connections;
        this.splitter = splitter;
        this.classifier = classifier;
        this.timeoutSeconds = Math.max(1, properties.getAiAgent().getValidationTimeoutSeconds());
    }

    public ValidationResult validate(long connectionId, String schemaName, String sql) {
        if (sql == null || sql.isBlank()) return ValidationResult.invalid("SQL 不能为空。");
        List<SqlScriptSplitter.StatementSegment> statements = splitter.split(sql);
        if (statements.size() != 1) return ValidationResult.invalid("只能校验一条 SQL 查询。");
        String statementSql = statements.get(0).sql();
        if (!classifier.isQuery(statementSql)) {
            return ValidationResult.invalid("候选 SQL 不是只读 SELECT、WITH 或 EXPLAIN 查询。");
        }
        try (Connection connection = connections.open(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             PreparedStatement statement = connection.prepareStatement(statementSql)) {
            try {
                statement.setQueryTimeout(timeoutSeconds);
            } catch (SQLFeatureNotSupportedException ignoredTimeout) {
                // 编译本身不执行查询；不支持超时设置的驱动仍可安全继续。
            }
            statement.getMetaData();
            return ValidationResult.valid("已通过目标数据库的只读编译校验（未执行查询）。");
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
