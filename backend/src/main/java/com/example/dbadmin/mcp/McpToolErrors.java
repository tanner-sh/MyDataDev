package com.example.dbadmin.mcp;

import com.example.dbadmin.api.ApiProblemException;

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
            message = "请求已取消，数据库侧的语句已请求中止。";
        } else if (isTimeout(error)) {
            code = SQL_TIMEOUT;
            message = (timeoutSeconds > 0 ? "语句执行超过 " + timeoutSeconds + " 秒" : "语句执行超时") + "，已请求数据库取消该语句。" + channel.timeoutHint
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
        McpToolException translated = new McpToolException(code, "[" + code + "] " + message);
        translated.addSuppressed(error);
        return translated;
    }

    /**
     * 超时的样子各家不同：标准驱动抛 {@link SQLTimeoutException}；PostgreSQL 是 SQLState 57014；
     * Oracle 系驱动常以 ORA-01013（厂商码 1013）报出被中止的调用。
     */
    static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLTimeoutException) return true;
            if (current instanceof SQLException sql && !"HY008".equals(sql.getSQLState())) {
                if ("57014".equals(sql.getSQLState()) || sql.getErrorCode() == 1013) return true;
                String text = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(Locale.ROOT);
                if (text.contains("timeout") || text.contains("timed out")) return true;
            }
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
