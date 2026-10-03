package com.example.dbadmin.service;

import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.core.DatabaseServerInfo;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.model.DbConnection;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class DatabaseCapabilityService {
    public record Feature(String key, String label, String status, String reason) {}
    public record Report(DatabaseServerInfo server, List<Feature> features) {}
    private final ConnectionService connections;
    private final DialectRegistry dialects;
    public DatabaseCapabilityService(ConnectionService connections, DialectRegistry dialects) {
        this.connections = connections; this.dialects = dialects;
    }
    public Report inspect(long id, List<ConnectionPermission> permissions) throws Exception {
        var db = connections.require(id);
        var server = connections.serverInfo(id);
        var features = new ArrayList<>(base(db, dialects.dialectFor(db).capabilities(), server, permissions));
        if (features.stream().anyMatch(f -> !f.status().equals("UNAVAILABLE"))) {
            try (var connection = connections.open(id)) {
                var metadata = connection.getMetaData();
                if (db.dbType().equalsIgnoreCase("sqlserver") && metadata.getDatabaseMajorVersion() < 11)
                    replace(features, "tableBrowse", "SQL Server 2012 以下版本不支持当前分页语法。");
                try {
                    if (connection.isReadOnly()) {
                        replace(features, "tableEdit", "数据库会话为只读。");
                        replace(features, "tableDesign", "数据库会话为只读。");
                    }
                } catch (java.sql.SQLException ignored) { /* Some drivers do not expose the session hint. */ }
                try {
                    boolean transactions = metadata.supportsTransactions();
                    features.add(new Feature("transactions", "事务", transactions ? "AVAILABLE" : "UNAVAILABLE",
                            transactions ? "驱动报告支持事务；实际提交权限由数据库判定。" : "驱动报告不支持事务。"));
                } catch (java.sql.SQLException ignored) {
                    features.add(new Feature("transactions", "事务", "UNVERIFIED", "驱动未提供事务能力信息。"));
                }
                if (db.dbType().equalsIgnoreCase("sqlserver")) {
                    try (var statement = connection.createStatement()) {
                        statement.setQueryTimeout(5);
                        try (var rows = statement.executeQuery("SELECT HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'SHOWPLAN')")) {
                            if (rows.next() && rows.getInt(1) == 0 && !rows.wasNull()) replace(features, "explain", "当前数据库账号缺少 SHOWPLAN 权限。");
                        }
                    } catch (java.sql.SQLException ignored) { /* Object-specific rights remain unverified. */ }
                }
            }
        }
        return new Report(server, List.copyOf(features));
    }
    static List<Feature> base(DbConnection db, DatabaseCapabilities caps, DatabaseServerInfo server, List<ConnectionPermission> permissions) {
        List<Feature> result = new ArrayList<>();
        add(result, "tableBrowse", "表浏览", caps.tableBrowse(), false, ConnectionPermission.QUERY, db, server, permissions);
        add(result, "tableEdit", "数据编辑", caps.tableEdit(), true, ConnectionPermission.DATA_WRITE, db, server, permissions);
        add(result, "tableDesign", "表结构设计", caps.tableDesign(), true, ConnectionPermission.DDL, db, server, permissions);
        add(result, "explain", "执行计划", caps.explain(), false, ConnectionPermission.QUERY, db, server, permissions);
        add(result, "export", "数据导出", true, false, ConnectionPermission.EXPORT, db, server, permissions);
        return result;
    }
    private static void add(List<Feature> result, String key, String label, boolean supported, boolean mutation,
            ConnectionPermission permission, DbConnection db, DatabaseServerInfo server, List<ConnectionPermission> permissions) {
        String reason = null;
        if (!supported) reason = "当前数据库方言尚未实现此功能。";
        else if (DatabaseServerInfo.needsOceanBaseCheck(db.dbType()) && !Boolean.TRUE.equals(server.typeMatches()))
            reason = "OceanBase 兼容模式不匹配或尚未验证。";
        else if (!permissions.contains(permission) && !permissions.contains(ConnectionPermission.CONNECTION_ADMIN)) reason = "当前应用账号缺少 " + permission + " 权限。";
        else if (mutation && db.readonly()) reason = "连接配置为只读。";
        result.add(new Feature(key, label, reason == null ? "UNVERIFIED" : "UNAVAILABLE", reason == null
                ? "方言支持且应用权限允许；目标对象的数据库权限在执行时验证。" : reason));
    }
    private static void replace(List<Feature> features, String key, String reason) {
        features.replaceAll(f -> f.key().equals(key) && !f.status().equals("UNAVAILABLE") ? new Feature(key, f.label(), "UNAVAILABLE", reason) : f);
    }
}
