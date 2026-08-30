package com.example.dbadmin.access;

/** 一条连接上可独立授予的功能权限。CONNECTION_ADMIN 隐含全部权限。 */
public enum ConnectionPermission {
    VIEW_METADATA,
    QUERY,
    DATA_WRITE,
    DDL,
    EXPORT,
    BACKUP_RESTORE,
    CONNECTION_ADMIN
}
