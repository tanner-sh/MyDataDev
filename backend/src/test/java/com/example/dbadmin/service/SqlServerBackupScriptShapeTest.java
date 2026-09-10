package com.example.dbadmin.service;

import com.example.dbadmin.core.MySqlDialect;
import com.example.dbadmin.core.PostgreSqlDialect;
import com.example.dbadmin.core.SqlServerDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL Server 的备份脚本必须按 GO 分批，否则它恢复不回去。
 *
 * <p>{@code SqlFileStatementReader} 在 SQL Server 上刻意不按分号切分 —— T-SQL 的
 * {@code BEGIN … END;} 里有内部分号，按分号切会把存储过程切碎 —— 只认独占一行的 GO。
 * 而备份写出去的脚本此前只有分号：整份文件于是被当成一条语句，恢复预检报
 * multi-statement be found，也就是说 SQL Server 上「备份完能不能恢复」压根不成立。
 * 这是实库回归找出来的，两端的约定要有测试钉住。</p>
 */
class SqlServerBackupScriptShapeTest {
    @TempDir Path directory;

    @Test
    void sqlServerSeparatesStatementsWithGoAndOthersWithSemicolonOnly() {
        assertThat(new SqlServerDialect().scriptStatementSeparator()).isEqualTo(";\nGO");
        assertThat(new MySqlDialect().scriptStatementSeparator()).isEqualTo(";");
        assertThat(new PostgreSqlDialect().scriptStatementSeparator()).isEqualTo(";");
    }

    @Test
    void aGoSeparatedSqlServerScriptSplitsAndParsesStatementByStatement() throws Exception {
        // 这份文本就是备份写出去的形状：文件头注释、建表、多行 INSERT、建索引，各自以 ";\nGO" 收尾。
        Path path = directory.resolve("backup.sql");
        Files.writeString(path, """
                -- MyDataDev SQL Backup
                -- Source-Db-Type: sqlserver

                -- Table structure: [dbo].[t]
                CREATE TABLE [dbo].[t] (
                  [id] bigint NOT NULL,
                  [name] nvarchar(200),
                  [amount] decimal(20,4),
                  PRIMARY KEY ([id])
                );
                GO

                -- Table: [dbo].[t]
                INSERT INTO [dbo].[t] ([id], [name], [amount]) VALUES
                  (2, NULL, NULL),
                  (3, N'', NULL),
                  (9007199254740993, N'中文 O''Reilly\\path
                第二行', 123456789012.3456);
                GO

                CREATE INDEX [idx_t] ON [dbo].[t] ([name]);
                GO
                """);

        List<String> chunks = new ArrayList<>();
        SqlStatementStream.read(path, "sqlserver", chunks::add);
        assertThat(chunks).as("按 GO 分成三条语句").hasSize(3);
        // 带内部换行和 '' 转义的多行 INSERT 必须完整落在一条里，不能被切开。
        assertThat(chunks.get(1)).contains("第二行").contains("O''Reilly");

        var analysis = new SqlRestoreTranslator().analyze(path, "sqlserver", "sqlserver", Map.of());
        assertThat(analysis.errors()).as("恢复预检不该有解析错误").isEmpty();
        assertThat(analysis.statementCount()).isEqualTo(3);
        assertThat(analysis.tables()).contains("dbo.t");
    }

    @Test
    void aSemicolonOnlySqlServerScriptIsStillOneChunk() throws Exception {
        // 反向钉住 reader 的约定：没有 GO 时 SQL Server 就是不切分。备份写出 GO 的必要性
        // 全部来自这一条 —— 哪天 reader 改成也按分号切，这条会失败，提醒一并回看备份那侧。
        Path path = directory.resolve("no-go.sql");
        Files.writeString(path, "CREATE TABLE [dbo].[t] ([id] bigint NOT NULL);\nCREATE INDEX [i] ON [dbo].[t] ([id]);\n");
        List<String> chunks = new ArrayList<>();
        SqlStatementStream.read(path, "sqlserver", chunks::add);
        assertThat(chunks).hasSize(1);
    }
}
