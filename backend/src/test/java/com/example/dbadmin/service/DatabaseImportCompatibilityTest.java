package com.example.dbadmin.service;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入脚本拿到真实数据库上执行。
 *
 * <p>{@code DataImportServiceTest} 只核对生成的文本，回答不了「这段文本目标库认不认」。Oracle 23
 * 之前不认多行 VALUES，而 CI 跑的恰好是 23，于是导入到旧版 Oracle 整批报 ORA-00933 这件事一直
 * 没被发现，直到夜间回归跑了 Oracle 21。跨库数据传输复用同一条生成管线，写入目标端的那一半也由
 * 这里覆盖。</p>
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseImportCompatibilityTest {
    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "sqlserver", "oracle"})
    void importScriptRunsOnTheTargetDatabase(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type)) {
            String table = f.table(f.pk("id") + ", " + f.varchar("name", 80));
            var columns = new LinkedHashSet<>(List.of(f.col("id"), f.col("name")));
            // 行数跨过一批：多行 VALUES 的库拆成两条语句，逐行的库每行一条，两种形状都真的执行一遍。
            int rows = DataImportService.ROWS_PER_STATEMENT + 1;
            StringBuilder csv = new StringBuilder("id,name\n");
            for (int id = 1; id < rows; id++) csv.append(id).append(",客户").append(id).append('\n');
            csv.append(rows).append(",O'Brien 中文\n");
            StringWriter script = new StringWriter();
            try (var reader = new CsvStreamReader(new StringReader(csv.toString()))) {
                DataImportService.convert(reader, script, f.dialect, f.schema, table, columns, "import.csv");
            }
            for (var segment : new SqlScriptSplitter().split(script.toString())) f.execute(segment.sql());
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(rows);
            assertThat(f.scalar("SELECT name FROM " + f.q(table) + " WHERE id=1")).isEqualTo("客户1");
            assertThat(f.scalar("SELECT name FROM " + f.q(table) + " WHERE id=" + rows)).isEqualTo("O'Brien 中文");
        }
    }
}
