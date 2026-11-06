package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 语句执行之外的输出：JDBC 的 SQLWarning 链（PostgreSQL 的 RAISE NOTICE、MySQL 的警告）与 Oracle 的
 * DBMS_OUTPUT 缓冲。例程编辑器与命令窗口共用。
 *
 * <p>DBMS_OUTPUT 不显式 ENABLE、不逐行 GET_LINE 就永远看不到，而 PL/SQL 里排查问题基本全靠它。
 * 整段吞异常：这是语句之外的诊断信息，读不回来不该把一次已经成功的执行改判成失败。</p>
 */
final class RoutineOutput {
    private RoutineOutput() {
    }

    /** 打开服务端的输出缓冲。失败无所谓：拿不到 DBMS_OUTPUT 只是少一份诊断信息。 */
    static void enable(Connection connection, DatabaseDialect dialect) {
        String sql = dialect.routineOutputEnableSql();
        if (sql == null) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception ignored) {
            // 见类注释。
        }
    }

    static List<String> read(Connection connection, DatabaseDialect dialect, Statement statement, int max) {
        List<String> messages = new ArrayList<>();
        try {
            collectWarnings(statement.getWarnings(), messages, max);
            collectWarnings(connection.getWarnings(), messages, max);
        } catch (Exception ignored) {
            // 读警告本身失败就算了。
        }
        String fetch = dialect.routineOutputFetchCall();
        if (fetch == null) return List.copyOf(messages);
        try (CallableStatement output = connection.prepareCall(fetch)) {
            output.registerOutParameter(1, Types.VARCHAR);
            output.registerOutParameter(2, Types.INTEGER);
            while (messages.size() < max) {
                output.execute();
                // 状态码非 0 表示缓冲读完了。这是 DBMS_OUTPUT.GET_LINE 的约定。
                if (output.getInt(2) != 0) break;
                String line = output.getString(1);
                if (line != null) messages.add(line);
            }
        } catch (Exception ignored) {
            // 缓冲没打开、没权限、或这个连接根本不是 Oracle：都不是语句本身的问题。
        }
        return List.copyOf(messages);
    }

    private static void collectWarnings(SQLWarning warning, List<String> messages, int max) {
        SQLWarning current = warning;
        while (current != null && messages.size() < max) {
            String text = current.getMessage();
            if (text != null && !text.isBlank()) messages.add(text.strip());
            current = current.getNextWarning();
        }
    }
}
