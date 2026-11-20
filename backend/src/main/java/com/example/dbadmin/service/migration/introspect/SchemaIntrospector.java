package com.example.dbadmin.service.migration.introspect;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;

import java.sql.Connection;
import java.util.List;

/**
 * 源数据库深度元数据抽取器接口。
 *
 * <p>标准 JDBC {@code DatabaseMetaData} 拿不到诸如 Oracle {@code CHAR/BYTE} 语义、裸 {@code NUMBER}、
 * 表注释、CHECK 约束、函数索引、Sequence 当前值等关键信息；各家族实现通过系统字典精准抽取，
 * 权限不足时再优雅回退到通用 JDBC 抽取。</p>
 */
public interface SchemaIntrospector {
    boolean supports(DbTargetDialect dialect);

    CanonicalSchema introspect(
            Connection connection,
            DbConnection dbConnection,
            DatabaseDialect dialect,
            DbTargetDialect sourceDialect,
            String schemaName,
            MigrationOptions options,
            List<String> warnings
    ) throws Exception;
}
