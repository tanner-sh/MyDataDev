package com.example.dbadmin.mcp;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.audit.AuditRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Locale;

/**
 * MCP 工具报错的统一形状：{@code [CODE] 中文说明}。
 *
 * <p>SDK 只把异常根因的 message 当文本交给客户端，{@link ApiProblemException} 里的错误码在这一步就丢了 ——
 * Agent 拿到的只是一句中文，分不清「要生产确认」「超时」「并发已满」「SQL 写错了」，只能去猜文案。
 * 错误码放在固定位置，Agent 才能据此决定是补参数重试、换异步通道，还是去改 SQL。</p>
 *
 * <p>抛出的 {@link McpToolException} 不挂 cause：SDK 会顺着 cause 链取最底层的 message，挂上就等于把前缀扔了。
 * 原异常放进 suppressed，日志里仍看得到。</p>
 */
final class McpToolErrors {
    static final String SQL_TIMEOUT = "SQL_TIMEOUT";
    private static final Logger log = LoggerFactory.getLogger(McpToolErrors.class);

    private McpToolErrors() { }

    /** 同步工具的超时提示要说清：超时的是哪条通道、上限在哪改、长任务该走哪里。 */
    enum Channel {
        QUERY("可在 MCP 设置里调高「查询超时」（上限 300 秒），或缩小查询范围后重试。"),
        EXECUTE("超时不能证明已回滚：重试前先用 db_query 核对结果。长时间运行的写操作请改用 db_execute_script"
                + "（后台执行、可查询进度与取消），或在 MCP 设置里调高「写操作超时」。"),
        OTHER("");

        private final String timeoutHint;

        Channel(String timeoutHint) { this.timeoutHint = timeoutHint; }
    }

    static McpToolException translate(Exception error, Channel channel, int timeoutSeconds) {
        if (error instanceof McpToolException already) return already;
        String code;
        String message;
        if (isCancelled(error)) {
            code = "CANCELLED";
            message = "请求已取消；此回执不确认数据库语句已终止或完成回滚。";
        } else if (isConnectionUnavailable(error)) {
            code = "CONNECTION_UNAVAILABLE";
            message = "无法取得或保持数据库连接，语句可能根本没有发出。只读调用可以稍后直接重试；写操作请先用 db_query 核对结果"
                    + "（后台脚本用 db_list_script_tasks 查看任务真实状态）再决定是否重试。数据库原文：" + rootMessage(error);
        } else if (isTimeout(error)) {
            code = SQL_TIMEOUT;
            message = "数据库报告语句超时或中止" + (timeoutSeconds > 0 ? "（配置的语句超时为 " + timeoutSeconds + " 秒）" : "")
                    + "；此回执不确认数据库语句已终止或完成回滚。" + channel.timeoutHint
                    + " 数据库原文：" + rootMessage(error);
        } else if (error instanceof ApiProblemException problem) {
            code = problem.code();
            message = problem.getMessage() + confirmationHint(problem);
        } else if (sqlCause(error) != null) {
            SQLException sql = sqlCause(error);
            code = "SQL_ERROR";
            String state = sql.getSQLState() == null ? "" : "SQLState=" + sql.getSQLState() + "，";
            message = "数据库返回错误（" + state + "厂商错误码=" + sql.getErrorCode() + "）：" + rootMessage(error);
        } else if (error instanceof IllegalArgumentException) {
            code = "INVALID_REQUEST";
            message = rootMessage(error);
        } else if (error instanceof IllegalStateException) {
            code = "REJECTED";
            message = rootMessage(error);
        } else {
            code = "INTERNAL_ERROR";
            message = rootMessage(error);
        }
        var context = AuditRequestContext.current();
        String requestId = context == null ? null : context.requestId();
        if ("CONNECTION_UNAVAILABLE".equals(code) || SQL_TIMEOUT.equals(code) || "SQL_ERROR".equals(code) || "INTERNAL_ERROR".equals(code)) {
            // 工具只显示一段错误文本；服务端保留含 suppressed 收尾异常的根堆栈，按同一 ID 关联审计连接与工具名。
            log.warn("MCP 工具失败 requestId={} channel={} code={}", requestId, channel, code, error);
        }
        String correlation = requestId == null ? "" : " [requestId=" + requestId + "]";
        McpToolException translated = new McpToolException(code, "[" + code + "] " + message + correlation);
        translated.addSuppressed(error);
        return translated;
    }

    /**
     * 超时的样子各家不同：标准驱动抛 {@link SQLTimeoutException}；PostgreSQL 是 SQLState 57014；
     * Oracle 系驱动常以 ORA-01013（厂商码 1013）报出被中止的调用，OceanBase 的服务端超时是内部错误码 -4012。
     * 只认这些明确的信号：连接池「request timed out」之类的连接问题由 {@link #isConnectionUnavailable} 先接走，
     * 把它报成「语句超时、已请求取消」会让调用方以为语句跑过。
     */
    static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLTimeoutException) return true;
            if (current instanceof SQLException sql && !"HY008".equals(sql.getSQLState())) {
                if ("57014".equals(sql.getSQLState()) || sql.getErrorCode() == 1013) return true;
                String text = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(Locale.ROOT);
                if (text.matches("(?s).*(?:-4012\\b|statement timeout|query timeout|timeout.*cancel|cancel.*timeout).*")) return true;
            }
            if (current == current.getCause()) break;
        }
        return false;
    }

    /** 取不到连接（池满、超时）或连接中途断开：SQLState 08 类、JDBC 的连接异常类型。 */
    static boolean isConnectionUnavailable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLTransientConnectionException || current instanceof java.sql.SQLNonTransientConnectionException) return true;
            if (current instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) return true;
            if (current == current.getCause()) break;
        }
        return false;
    }

    /** RequestCancellation 用 SQLState HY008 标记「MCP 客户端发了取消通知」。 */
    private static boolean isCancelled(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "HY008".equals(sql.getSQLState())) return true;
            if (current == current.getCause()) break;
        }
        return false;
    }

    private static SQLException sqlCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql;
            if (current == current.getCause()) break;
        }
        return null;
    }

    private static String confirmationHint(ApiProblemException problem) {
        if ("UNSCOPED_MUTATION_CONFIRMATION_REQUIRED".equals(problem.code())) return "确认无误后设置 unscopedMutationConfirmed=true 重试。";
        Object text = problem.details().get("confirmationText");
        return text == null ? "" : "请把 productionConfirmation 设为「" + text + "」后重试。";
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.isBlank()) message = error.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    static final class McpToolException extends RuntimeException {
        private final String code;

        McpToolException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() { return code; }
    }
}
