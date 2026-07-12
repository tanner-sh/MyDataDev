package com.example.dbadmin.core;

import com.example.dbadmin.model.DbConnection;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DialectRegistry {
    private final List<DatabaseDialect> dialects = List.of(
            new OceanBaseMySqlDialect(),
            new OceanBaseOracleDialect(),
            new OracleDialect(),
            new MySqlDialect(),
            new DamengDialect(),
            new PostgreSqlDialect(),
            new SqlServerDialect(),
            new SqliteDialect(),
            new ClickHouseDialect(),
            new H2Dialect(),
            new DefaultDialect()
    );

    public DatabaseDialect dialectFor(DbConnection connection) {
        return dialects.stream()
                .filter(dialect -> dialect.supports(connection.dbType(), connection.jdbcUrl()))
                .findFirst()
                .orElseThrow();
    }
}
