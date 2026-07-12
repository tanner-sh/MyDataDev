package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.util.List;

public class OceanBaseOracleDialect extends OracleDialect {
    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of());
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "oceanbase-oracle".equalsIgnoreCase(dbType);
    }
}
