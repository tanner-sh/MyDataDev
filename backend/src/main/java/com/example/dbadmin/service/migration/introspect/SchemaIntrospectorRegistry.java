package com.example.dbadmin.service.migration.introspect;

import com.example.dbadmin.service.migration.model.DbTargetDialect;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按源方言路由到对应的深度元数据抽取器。 */
@Component
public class SchemaIntrospectorRegistry {
    private final List<SchemaIntrospector> introspectors = List.of(
            new OracleFamilyIntrospector(),
            new MySqlFamilyIntrospector(),
            new PostgresSchemaIntrospector(),
            new SqlServerSchemaIntrospector(),
            new SqliteSchemaIntrospector(),
            new ClickHouseSchemaIntrospector(),
            new JdbcBaseIntrospector()
    );

    public SchemaIntrospector forDialect(DbTargetDialect dialect) {
        return introspectors.stream()
                .filter(i -> i.supports(dialect))
                .findFirst()
                .orElseThrow();
    }
}
