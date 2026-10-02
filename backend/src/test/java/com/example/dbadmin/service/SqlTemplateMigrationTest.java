package com.example.dbadmin.service;

import db.migration.V22__SqlTemplateParameters;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SqlTemplateMigrationTest {
    @Test void upgradesExistingSnippetsWithoutChangingTheirSql() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:template-migration")) {
            connection.createStatement().execute("create table sql_snippet(id bigint primary key, sql_text clob)");
            connection.createStatement().execute("insert into sql_snippet values (1, 'select 1')");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V22__SqlTemplateParameters().migrate(context);
            new V22__SqlTemplateParameters().migrate(context);
            try (var rs = connection.createStatement().executeQuery("select sql_text, parameters_json from sql_snippet")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("select 1");
                assertThat(rs.getString(2)).isEqualTo("[]");
            }
        }
    }
}
