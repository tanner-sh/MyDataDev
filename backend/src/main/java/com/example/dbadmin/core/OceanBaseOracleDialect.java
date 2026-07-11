package com.example.dbadmin.core;

public class OceanBaseOracleDialect extends OracleDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "oceanbase-oracle".equalsIgnoreCase(dbType);
    }
}
