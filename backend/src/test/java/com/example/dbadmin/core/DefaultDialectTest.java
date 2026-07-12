package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.IndexDesign;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultDialectTest {
    private final DefaultDialect dialect = new DefaultDialect();

    @Test
    void resolvesJdbcSchemaBeforeCatalog() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn("PUBLIC");
        when(connection.getCatalog()).thenReturn("demo");

        assertThat(dialect.currentSchema(connection)).isEqualTo("PUBLIC");
    }

    @Test
    void fallsBackToCurrentCatalogWhenSchemaIsUnavailable() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn(null);
        when(connection.getCatalog()).thenReturn("demo");

        assertThat(dialect.currentSchema(connection)).isEqualTo("demo");
    }

    @Test
    void doesNotTreatPrimaryKeyBackingIndexOmittedByDesignerAsDeleted() {
        DefaultDialect dialect = new MySqlDialect();
        ObjectDetail original = new ObjectDetail(
                "demo", "users", "TABLE",
                List.of(new ColumnInfo("id", "BIGINT", 19, false, null, 1, null)),
                List.of(new IndexInfo("PRIMARY", "id", true, 1)),
                List.of("id"), "PRIMARY"
        );
        TableDesignRequest unchanged = new TableDesignRequest(
                "demo", "users",
                List.of(new ColumnDesign("id", "BIGINT", 19, false, null, "id", false)),
                List.of(), List.of("id"), null
        );

        assertThat(dialect.alterTableSql("demo", "users", original, unchanged)).isEmpty();
    }

    @Test
    void rejectsLiveColumnsThatWereNotPresentInTheSubmittedDesignerBaseline() {
        ObjectDetail live = new ObjectDetail(
                "demo", "users", "TABLE",
                List.of(
                        new ColumnInfo("id", "BIGINT", 19, false, null, 1, null),
                        new ColumnInfo("added_elsewhere", "VARCHAR", 40, true, null, 2, null)
                ),
                List.of(), List.of("id"), "pk_users"
        );
        TableDesignRequest stale = new TableDesignRequest(
                "demo", "users",
                List.of(new ColumnDesign("id", "BIGINT", 19, false, null, "id", false)),
                List.of(), List.of("id"), null
        );

        assertThatThrownBy(() -> dialect.alterTableSql("demo", "users", live, stale))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未加载的字段")
                .hasMessageContaining("added_elsewhere");
    }

    @Test
    void rejectsOmittedIndexesInsteadOfTurningOmissionIntoDeletion() {
        ObjectDetail live = new ObjectDetail(
                "demo", "users", "TABLE",
                List.of(new ColumnInfo("name", "VARCHAR", 40, true, null, 1, null)),
                List.of(new IndexInfo("idx_users_name", "name", false, 1)),
                List.of(), null
        );
        TableDesignRequest stale = new TableDesignRequest(
                "demo", "users",
                List.of(new ColumnDesign("name", "VARCHAR", 40, true, null, "name", false)),
                List.of(), List.of(), null
        );

        assertThatThrownBy(() -> dialect.alterTableSql("demo", "users", live, stale))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未加载的索引")
                .hasMessageContaining("idx_users_name");
    }

    @Test
    void keepsCaseSensitiveColumnsAndIndexesDistinct() {
        ObjectDetail original = new ObjectDetail(
                "PUBLIC", "case_values", "TABLE",
                List.of(
                        new ColumnInfo("Foo", "VARCHAR", 40, true, null, 1, null),
                        new ColumnInfo("foo", "VARCHAR", 40, true, null, 2, null)
                ),
                List.of(
                        new IndexInfo("IdxFoo", "Foo", false, 1),
                        new IndexInfo("idxfoo", "foo", false, 1)
                ),
                List.of(), null
        );
        TableDesignRequest unchanged = new TableDesignRequest(
                "PUBLIC", "case_values",
                List.of(
                        new ColumnDesign("Foo", "VARCHAR", 40, true, null, "Foo", false),
                        new ColumnDesign("foo", "VARCHAR", 40, true, null, "foo", false)
                ),
                List.of(
                        new IndexDesign("IdxFoo", List.of("Foo"), false, "IdxFoo", false),
                        new IndexDesign("idxfoo", List.of("foo"), false, "idxfoo", false)
                ),
                List.of(), null
        );

        assertThat(dialect.alterTableSql("PUBLIC", "case_values", original, unchanged)).isEmpty();
    }
}
