package com.example.dbadmin.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Live server identity. Never infers a tenant from a username or JDBC URL. */
public record DatabaseServerInfo(String product, String version, String driver, String tenantId,
        String tenantName, String compatibilityMode, Boolean typeMatches, List<String> warnings) {
    public static boolean needsOceanBaseCheck(String type) {
        return "oceanbase-mysql".equalsIgnoreCase(type) || "oceanbase-oracle".equalsIgnoreCase(type);
    }

    public static DatabaseServerInfo inspect(Connection connection, String type) throws SQLException {
        var metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        String version = metadata.getDatabaseProductVersion();
        String driver = metadata.getDriverName() + " " + metadata.getDriverVersion();
        if (!needsOceanBaseCheck(type)) return new DatabaseServerInfo(product, version, driver, null, null, null, null, List.of());
        List<String> warnings = new ArrayList<>();
        String[] tenant = null;
        // Detect the actual mode independently of the selected dialect. A mismatch must be reported,
        // rather than executing MySQL statements against an Oracle tenant (or vice versa).
        for (String sql : List.of(
                "SELECT TENANT_ID, TENANT_NAME, COMPATIBILITY_MODE FROM oceanbase.DBA_OB_TENANTS WHERE TENANT_ID = EFFECTIVE_TENANT_ID()",
                "SELECT TENANT_ID, TENANT_NAME, COMPATIBILITY_MODE FROM SYS.DBA_OB_TENANTS")) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.setMaxRows(2);
                try (var rows = statement.executeQuery(sql)) {
                    if (rows.next()) {
                        String[] found = {rows.getString(1), rows.getString(2), rows.getString(3)};
                        if (!rows.next()) { tenant = found; break; }
                    }
                }
            } catch (SQLException ignored) {
                // Mode-specific catalogs may not exist or may be inaccessible. Unknown is not verified.
            }
        }
        if (tenant == null || tenant[2] == null) {
            warnings.add("无法验证 OceanBase 租户及兼容模式，请检查版本和当前账号读取 DBA_OB_TENANTS 的权限。");
            return new DatabaseServerInfo(product, version, driver, null, null, null, null, List.copyOf(warnings));
        }
        String mode = tenant[2].toUpperCase(Locale.ROOT);
        boolean matches = mode.equals(type.equalsIgnoreCase("oceanbase-oracle") ? "ORACLE" : "MYSQL");
        if (!matches) warnings.add("连接类型与租户实际兼容模式不一致，请改为 OceanBase " + mode + " 模式。");
        return new DatabaseServerInfo("OceanBase", version, driver, tenant[0], tenant[1], mode, matches, List.copyOf(warnings));
    }

    public static void validateOceanBase(Connection connection, String type) throws SQLException {
        if (!needsOceanBaseCheck(type)) return;
        var info = inspect(connection, type);
        if (!Boolean.TRUE.equals(info.typeMatches())) {
            throw new SQLException(String.join(" ", info.warnings()));
        }
    }
}
