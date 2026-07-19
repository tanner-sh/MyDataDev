package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlRestoreTranslatorTest {
    private final SqlRestoreTranslator translator = new SqlRestoreTranslator();

    @TempDir
    Path tempDir;

    @Test
    void analyzesAllowedStatementsAndDiscoversNamespacesAndTables() throws Exception {
        Path file = sql("""
                CREATE TABLE `sales`.`orders` (`id` BIGINT AUTO_INCREMENT, `note` LONGTEXT, PRIMARY KEY (`id`));
                INSERT INTO `sales`.`orders` (`id`, `note`) VALUES (1, 'a;b');
                """);

        var analysis = translator.analyze(file, "mysql", "postgresql", Map.of("sales", "archive"));

        assertThat(analysis.statementCount()).isEqualTo(2);
        assertThat(analysis.namespaces()).containsExactly("sales");
        assertThat(analysis.tables()).containsExactly("sales.orders");
        assertThat(analysis.errors()).isEmpty();
    }

    @Test
    void translatesNamespaceAndPortableColumnTypes() throws Exception {
        Path file = sql("CREATE TABLE `sales`.`orders` (`id` BIGINT AUTO_INCREMENT, `note` LONGTEXT, `payload` LONGBLOB);");
        List<String> translated = new ArrayList<>();

        translator.translate(file, "mysql", "postgresql", Map.of("sales", "archive"), "SAFE",
                (index, statement, data) -> translated.add(statement));

        assertThat(translated).hasSize(1);
        assertThat(translated.get(0)).containsIgnoringCase("archive").containsIgnoringCase("orders")
                .containsIgnoringCase("BIGSERIAL").containsIgnoringCase("TEXT").containsIgnoringCase("BYTEA");
    }

    @Test
    void appendModeSkipsDdlAndKeepsInsert() throws Exception {
        Path file = sql("CREATE TABLE t (id INT); INSERT INTO t VALUES (1);");
        List<String> translated = new ArrayList<>();

        translator.translate(file, "mysql", "postgresql", Map.of(), "APPEND",
                (index, statement, data) -> translated.add(statement));

        assertThat(translated).hasSize(1);
        assertThat(translated.get(0)).containsIgnoringCase("INSERT INTO t");
    }

    @Test
    void rejectsDestructiveOrUnrelatedStatements() throws Exception {
        Path file = sql("DROP TABLE users;");

        var analysis = translator.analyze(file, "mysql", "mysql", Map.of());

        assertThat(analysis.errors()).singleElement().asString().contains("不允许恢复语句");
        assertThatThrownBy(() -> translator.translate(file, "mysql", "mysql", Map.of(), "SAFE", (i, s, d) -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许恢复语句");
    }

    private Path sql(String content) throws Exception {
        Path file = tempDir.resolve("backup.sql");
        Files.writeString(file, content);
        return file;
    }
}
