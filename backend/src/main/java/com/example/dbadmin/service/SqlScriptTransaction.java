package com.example.dbadmin.service;

import java.sql.Connection;
import java.sql.Statement;

/** One owned connection; never commits from close(), including cancellation and result-read failures. */
final class SqlScriptTransaction implements AutoCloseable {
    private final Connection connection;
    private final Runnable progress;
    long commits;
    long rollbacks;
    Long lastCommit;
    String outcome = "EXECUTING";
    private boolean finished;
    private boolean opaque;
    private boolean uncertain;

    SqlScriptTransaction(Connection connection, Runnable progress) throws Exception {
        this.connection = connection;
        this.progress = progress;
        connection.setAutoCommit(false);
    }

    boolean execute(Statement statement, String sql, long index) throws Exception {
        SqlScriptSyntax.validateScriptControl(sql);
        var control = SqlScriptSyntax.transaction(sql);
        opaque |= SqlScriptSyntax.opaqueBlock(sql) || new SqlStatementClassifier().classify(sql) == SqlStatementClassifier.Kind.DDL;
        if (control == SqlScriptSyntax.Transaction.COMMIT) { outcome = "COMMITTING"; progress.run(); }
        try {
            boolean result = statement.execute(sql);
            if (control == SqlScriptSyntax.Transaction.COMMIT) { commits++; lastCommit = index; }
            if (control == SqlScriptSyntax.Transaction.ROLLBACK) rollbacks++;
            outcome = "EXECUTING";
            if (control != SqlScriptSyntax.Transaction.NONE) progress.run();
            return result;
        } catch (Exception error) {
            if (control == SqlScriptSyntax.Transaction.COMMIT || error instanceof java.sql.SQLRecoverableException
                    || error instanceof java.sql.SQLNonTransientConnectionException) uncertain = true;
            throw error;
        }
    }

    void finish(boolean commit, long index) throws Exception {
        if (commit) {
            outcome = "COMMITTING";
            progress.run();
            try { connection.commit(); }
            catch (Exception error) { uncertain = true; throw error; }
            commits++;
            lastCommit = index;
            outcome = "COMMITTED";
        } else {
            connection.rollback();
            rollbacks++;
            outcome = "TAIL_ROLLED_BACK";
        }
        finished = true;
        progress.run();
    }

    void fail(Throwable error) {
        if (finished) return;
        if (error instanceof java.sql.SQLRecoverableException || error instanceof java.sql.SQLNonTransientConnectionException
                || (error instanceof java.sql.SQLException sqlError && sqlError.getSQLState() != null && sqlError.getSQLState().startsWith("08"))) uncertain = true;
        try {
            connection.rollback();
            rollbacks++;
            outcome = uncertain ? "UNKNOWN" : opaque ? "ROLLED_BACK_PARTIAL_POSSIBLE" : "ROLLED_BACK";
        } catch (Exception rollbackError) {
            error.addSuppressed(rollbackError);
            outcome = "UNKNOWN";
            try { connection.abort(Runnable::run); } catch (Exception abortError) { error.addSuppressed(abortError); }
        }
        finished = true;
        try { progress.run(); } catch (Exception progressError) { error.addSuppressed(progressError); }
    }

    String summary() {
        String ending = switch (outcome) {
            case "COMMITTED" -> "剩余事务已提交";
            case "TAIL_ROLLED_BACK" -> "剩余未提交事务已回滚，仅保留此前已提交内容";
            case "ROLLED_BACK" -> "当前未提交事务已回滚";
            case "ROLLED_BACK_PARTIAL_POSSIBLE" -> "当前事务已回滚，过程或 DDL 可能已提交部分内容";
            default -> "事务结果未知，请核对数据库";
        };
        return ending + "；确认提交 " + commits + " 次，回滚 " + rollbacks + " 次（不含过程内部操作）。此前提交不会撤销。";
    }

    @Override public void close() throws Exception {
        if (!finished) {
            Exception interrupted = new Exception("脚本未完成，正在回滚当前事务。");
            fail(interrupted);
            if (interrupted.getSuppressed().length > 0) throw interrupted;
        }
    }
}
