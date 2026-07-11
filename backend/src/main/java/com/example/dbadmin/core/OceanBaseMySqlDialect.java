package com.example.dbadmin.core;

public class OceanBaseMySqlDialect extends MySqlDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "oceanbase-mysql".equalsIgnoreCase(dbType);
    }
}
