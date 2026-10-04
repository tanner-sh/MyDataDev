package com.example.dbadmin.service;

/** A bounded pool acquisition timeout while every connection is in use. */
public final class PoolCapacityException extends java.sql.SQLTransientConnectionException {
    public PoolCapacityException(java.sql.SQLException cause) {
        super("数据库连接池暂时已满，请等待已有操作结束后重试。", cause.getSQLState(), cause.getErrorCode(), cause);
    }
}
