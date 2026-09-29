package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.ApiDtos.DataDiffRequest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseDataDiffCompatibilityTest {
    /** 数字不能强制经字符流读取，尤其需要 Oracle/SQL Server 的真实驱动验证。 */
    @ParameterizedTest
    @ValueSource(strings = {"mysql", "mariadb", "postgresql", "sqlserver", "oracle"})
    void boundedReadsPreserveScalarAndTextValuesAndRejectOversizeCells(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type)) {
            // Oracle 默认可能按字节限制 VARCHAR2；后面的 100 个汉字占 300 个 UTF-8 字节。
            // 先让样本能存入数据库，再验证应用的 40 字符上限，不能提前撞上数据库列宽。
            String columns = f.pk("id") + ", " + f.decimal("amount", 12, 2) + ", "
                    + f.varchar("note", 400) + ", " + f.timestamp("created_at", 6);
            String source = f.table(columns), target = f.table(columns);
            f.insert(source, "1, 123.45, " + f.text("原文中文") + ", CURRENT_TIMESTAMP", "2, NULL, NULL, NULL");
            f.execute("INSERT INTO " + f.q(target) + " SELECT * FROM " + f.q(source));
            DataDiffService service = new DataDiffService(f.connections, f.metadata, f.dialects, f.audit, f.properties);
            DataDiffRequest request = new DataDiffRequest(1L, f.schema, source, 1L, f.schema, target, List.of(), false);
            assertThat(service.compare(request, "ci").summary().identical()).isEqualTo(2);

            f.execute("UPDATE " + f.q(target) + " SET note=" + f.text("修改后") + " WHERE id=1");
            var different = service.compare(request, "ci");
            assertThat(different.summary().different()).isEqualTo(1);
            assertThat(different.script()).hasSize(1);
            assertThat(different.script().get(0)).contains("原文中文");

            f.properties.getDataDiff().setMaxCellChars(40);
            f.execute("UPDATE " + f.q(source) + " SET note=" + f.text("长".repeat(100)) + " WHERE id=1");
            assertThatThrownBy(() -> service.compare(request, "ci"))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            error -> assertThat(error.code()).isEqualTo("DATA_DIFF_CELL_LIMIT"));
        }
    }
}
