package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.PostgreSqlDialect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL 是这个工具第二常见的目标库，此前只有 SQL 备份，没有原生 pg_dump/pg_restore。
 */
class PostgresNativeToolsTest {
    @Test
    void postgresDeclaresItsNativeBackupAndRestoreMethods() {
        var capabilities = new PostgreSqlDialect().capabilities();

        assertThat(capabilities.nativeBackupMethods()).containsExactly("PG_DUMP");
        assertThat(capabilities.nativeRestoreMethods()).containsExactly("PG_RESTORE");
    }

    @Test
    void theToolLocatorKnowsBothBinaries() {
        NativeToolLocator locator = new NativeToolLocator(new AppProperties());

        assertThat(NativeToolLocator.Tool.PG_DUMP.displayName()).contains("pg_dump");
        assertThat(NativeToolLocator.Tool.PG_RESTORE.displayName()).contains("pg_restore");
        // 探测不到也只是 available=false，不该抛异常。
        assertThat(locator.detect(NativeToolLocator.Tool.PG_DUMP)).isNotNull();
        assertThat(locator.detect(NativeToolLocator.Tool.PG_RESTORE)).isNotNull();
    }

    @Test
    void configuredPathsAreHonoured() {
        AppProperties properties = new AppProperties();
        properties.getNativeTools().setPgDumpPath("/opt/pg/bin/pg_dump");
        properties.getNativeTools().setPgRestorePath("/opt/pg/bin/pg_restore");

        assertThat(properties.getNativeTools().getPgDumpPath()).isEqualTo("/opt/pg/bin/pg_dump");
        assertThat(properties.getNativeTools().getPgRestorePath()).isEqualTo("/opt/pg/bin/pg_restore");
    }

    @Test
    void anOverrideNameMustStillLookLikeTheRightBinary() {
        NativeToolLocator locator = new NativeToolLocator(new AppProperties());

        assertThatThrownBy(() -> locator.validateOverrideName(NativeToolLocator.Tool.PG_DUMP, "/bin/sh"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
