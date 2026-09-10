package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeToolLocatorTest {
    @TempDir
    Path tempDir;

    @Test
    void configuredPathHasPriorityAndReturnsCanonicalExecutable() throws Exception {
        Path configured = tool(tempDir.resolve("configured"), "mysqldump", "mysqldump  Ver 8.4.0", 0);
        Path fromPath = tool(tempDir.resolve("path"), "mysqldump", "mysqldump  Ver 8.0.0", 0);
        AppProperties properties = new AppProperties();
        properties.getNativeTools().setMysqldumpPath(configured.toString());
        NativeToolLocator locator = new NativeToolLocator(properties, Map.of("PATH", fromPath.getParent().toString()), "Linux");

        var resolved = locator.resolve(NativeToolLocator.Tool.MYSQLDUMP, null);

        assertThat(resolved.path()).isEqualTo(configured.toRealPath());
        assertThat(resolved.source()).isEqualTo("CONFIG");
        assertThat(resolved.version()).contains("8.4.0");
    }

    @Test
    void resolvesFromHomePathAndExtraDirectories() throws Exception {
        Path mysqlHome = tempDir.resolve("mysql-home");
        Path mysql = tool(mysqlHome.resolve("bin"), "mysql", "mysql  Ver 8.0", 0);
        AppProperties properties = new AppProperties();
        NativeToolLocator locator = new NativeToolLocator(properties, Map.of("MYSQL_HOME", mysqlHome.toString()), "Linux");

        assertThat(locator.resolve(NativeToolLocator.Tool.MYSQL, null).path()).isEqualTo(mysql.toRealPath());

        Path exp = tool(tempDir.resolve("oracle-tools"), "exp", "Export: Release 19", 0);
        properties.getNativeTools().setExtraSearchPaths(java.util.List.of(exp.getParent().toString()));
        NativeToolLocator extraLocator = new NativeToolLocator(properties, Map.of(), "Linux");
        assertThat(extraLocator.resolve(NativeToolLocator.Tool.ORACLE_EXP, null).source()).isEqualTo("EXTRA_PATH");
    }

    @Test
    void manualCommandNameUsesProcessPathAndManualAbsolutePathIsValidated() throws Exception {
        Path imp = tool(tempDir.resolve("oracle"), "imp", "Import: Release 19", 0);
        NativeToolLocator locator = new NativeToolLocator(new AppProperties(), Map.of("PATH", imp.getParent().toString()), "Linux");

        assertThat(locator.resolve(NativeToolLocator.Tool.ORACLE_IMP, "imp").source()).isEqualTo("MANUAL_PATH");
        assertThat(locator.resolve(NativeToolLocator.Tool.ORACLE_IMP, imp.toString()).source()).isEqualTo("MANUAL");
        assertThatThrownBy(() -> locator.validateOverrideName(NativeToolLocator.Tool.ORACLE_IMP, "/usr/bin/exp"))
                .hasMessageContaining("Oracle imp");
    }

    @Test
    void supportsWindowsExecutableNamesWithoutUsingShell() {
        NativeToolLocator locator = new NativeToolLocator(new AppProperties(), Map.of(), "Windows 11");

        assertThatCode(() -> locator.validateOverrideName(NativeToolLocator.Tool.MYSQL, "C:\\mysql\\bin\\mysql.exe"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> locator.validateOverrideName(NativeToolLocator.Tool.MYSQL, "C:\\mysql\\bin\\mysql"))
                .hasMessageContaining("mysql");
    }

    @Test
    void reportsMissingAndTimedOutTools() throws Exception {
        NativeToolLocator missing = new NativeToolLocator(new AppProperties(), Map.of("PATH", tempDir.toString()), "Linux");
        assertThatThrownBy(() -> missing.resolve(NativeToolLocator.Tool.ORACLE_IMP, null))
                .hasMessageContaining("未在应用服务器上找到")
                .hasMessageContaining("app.native-tools");

        Path slow = tool(tempDir.resolve("slow"), "mysql", "mysql", 3);
        AppProperties properties = new AppProperties();
        properties.getNativeTools().setProbeTimeoutSeconds(1);
        NativeToolLocator timeout = new NativeToolLocator(properties, Map.of("PATH", slow.getParent().toString()), "Linux");
        assertThatThrownBy(() -> timeout.resolve(NativeToolLocator.Tool.MYSQL, "mysql"))
                .hasMessageContaining("版本探测超时");
    }

    /**
     * MariaDB 的客户端叫 mariadb-dump / mariadb —— 从 10.5 起那才是正名，mysqldump 只是仍在
     * 但正被移除的兼容软链接。不认这两个名字的话，MariaDB 用户把工具路径指到自己那份客户端上
     * 会被直接拒，而拿 MySQL 的 mysqldump 去打 MariaDB 又会因为版本探测差异失败 —— 等于
     * MariaDB 的原生备份根本没法用。实库回归里 MariaDB 那个作业正是用 mariadb-dump 跑的。
     */
    @Test
    void acceptsMariaDbClientNamesForTheMysqlFamilyTools() throws Exception {
        Path dump = tool(tempDir.resolve("mariadb"), "mariadb-dump", "mariadb-dump  Ver 11.4", 0);
        NativeToolLocator locator = new NativeToolLocator(new AppProperties(), Map.of(), "Linux");

        assertThatCode(() -> locator.validateOverrideName(NativeToolLocator.Tool.MYSQLDUMP, "/usr/bin/mariadb-dump"))
                .doesNotThrowAnyException();
        assertThatCode(() -> locator.validateOverrideName(NativeToolLocator.Tool.MYSQL, "/usr/bin/mariadb"))
                .doesNotThrowAnyException();
        assertThat(locator.resolve(NativeToolLocator.Tool.MYSQLDUMP, dump.toString()).version()).contains("11.4");
        // 名字对不上还是要拒 —— 放宽的只有 MariaDB 这两个正名。
        assertThatThrownBy(() -> locator.validateOverrideName(NativeToolLocator.Tool.MYSQLDUMP, "/usr/bin/pg_dump"))
                .hasMessageContaining("mysqldump");
    }

    private Path tool(Path directory, String name, String version, int sleepSeconds) throws Exception {
        Files.createDirectories(directory);
        Path path = directory.resolve(name);
        Files.writeString(path, "#!/bin/sh\n" + (sleepSeconds > 0 ? "sleep " + sleepSeconds + "\n" : "") + "printf '%s\\n' '" + version + "'\n");
        path.toFile().setExecutable(true);
        return path;
    }
}
