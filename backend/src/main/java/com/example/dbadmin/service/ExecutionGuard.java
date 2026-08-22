package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.model.DbConnection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExecutionGuard {
    public void requireQueryAllowed(DbConnection connection, SqlStatementClassifier.Kind kind, String productionConfirmation) {
        boolean mutation = kind != SqlStatementClassifier.Kind.QUERY;
        requireAllowed(connection, mutation, productionConfirmation);
    }

    public void requireMutationAllowed(DbConnection connection, String productionConfirmation) {
        requireAllowed(connection, true, productionConfirmation);
    }

    private void requireAllowed(DbConnection connection, boolean mutation, String productionConfirmation) {
        if (mutation && connection.readonly()) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "READONLY_CONNECTION",
                    "当前连接为只读连接，不允许执行写入或结构变更。"
            );
        }
        // Arbitrary SELECT statements can invoke user-defined routines with
        // side effects. Require the exact production confirmation for every
        // free-form SQL operation; generated table-browse queries do not pass
        // through this guard.
        //
        // 确认串通过请求头传输，而 HTTP 头值只能是 ISO-8859-1，所以前端会先做
        // encodeURIComponent；这里统一还原后再比较。详见 ProductionConfirmationCodec。
        String confirmation = ProductionConfirmationCodec.decode(productionConfirmation);
        if ("prod".equalsIgnoreCase(connection.environment()) && !connection.name().equals(confirmation)) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "PRODUCTION_CONFIRMATION_REQUIRED",
                    mutation ? "生产连接上的写操作需要输入连接名确认。" : "生产连接上的 SQL 需要输入连接名确认。",
                    Map.of("confirmationText", connection.name())
            );
        }
    }
}
