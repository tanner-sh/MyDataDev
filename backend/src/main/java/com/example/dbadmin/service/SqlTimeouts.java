package com.example.dbadmin.service;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Locale;

/**
 * 「这次失败是不是语句超时」的唯一判定。界面（{@code ApiExceptionHandler}、例程调用）与 MCP 共用这一份。
 *
 * <p>超时的样子各家不同：标准驱动抛 {@link SQLTimeoutException}；PostgreSQL 是 SQLState 57014；
 * Oracle 系驱动常以 ORA-01013（厂商码 1013）报出被中止的调用，OceanBase 的服务端超时是内部错误码 -4012。
 * 只认这些明确的信号：连接池「request timed out」之类的连接问题不算 —— 把它报成「语句超时、已中止」
 * 会让人以为语句跑过。</p>
 *
 * <p>注意 57014 与 ORA-01013 同样是「用户取消」的报法，驱动层分不出两者。能分的调用方（登记了
 * {@link SqlExecutionRegistry} 的执行）应先问 {@link SqlExecutionRegistry#cancelRequested}。</p>
 */
public final class SqlTimeouts {
    private SqlTimeouts() { }

    public static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLTimeoutException) return true;
            // HY008 是 RequestCancellation 标记的「客户端取消」，不是超时。
            if (current instanceof SQLException sql && !"HY008".equals(sql.getSQLState())) {
                if ("57014".equals(sql.getSQLState()) || sql.getErrorCode() == 1013) return true;
                String text = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(Locale.ROOT);
                if (text.matches("(?s).*(?:-4012\\b|statement timeout|query timeout|timeout.*cancel|cancel.*timeout).*")) return true;
            }
            if (current == current.getCause()) break;
        }
        return false;
    }
}
