package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查询结果的可编辑判定。只有「单表来源 + 有稳定行定位字段 + 字段在结果集里」三条同时
 * 成立才发令牌，其余情况必须给出说明而不是静默不可编辑。
 */
class SqlServiceEditableResultTest {
    private record Fixture(SqlService sql, String url) {
    }

    private Fixture fixture(boolean readonly) throws Exception {
        String url = "jdbc:h2:mem:editable-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")).execute("""
                CREATE TABLE customers(id INT PRIMARY KEY, name VARCHAR(80), city VARCHAR(40));
                INSERT INTO customers VALUES (1, 'Alice', '上海'), (2, 'Bob', '北京');
                CREATE TABLE no_key(a INT, b INT);
                INSERT INTO no_key VALUES (1, 2);
                CREATE TABLE orders(id INT PRIMARY KEY, customer_id INT);
                INSERT INTO orders VALUES (10, 1);
                """);
        DbConnection model = new DbConnection(1L, "h2", "h2", url, "sa", "", "dev", readonly, Instant.now(), Instant.now());
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(model);
        when(connections.open(anyLong())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), any())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));

        DialectRegistry dialects = new DialectRegistry();
        MetadataCacheService cache = new MetadataCacheService();
        AuditRepository audit = mock(AuditRepository.class);
        MetadataService metadata = new MetadataService(connections, dialects, audit, cache, new ExecutionGuard());
        AppProperties properties = new AppProperties();
        CryptoService crypto = new CryptoService("test-key-test-key-test-key-32byt");
        DataEditService dataEdit = new DataEditService(
                metadata, connections, audit, dialects, properties,
                new TableCursorCodec(new ObjectMapper(), crypto),
                new RowLocatorCodec(new ObjectMapper(), crypto),
                new ExecutionGuard()
        );
        SqlService sql = new SqlService(
                connections, properties, audit, dialects, mock(SqlHistoryRepository.class),
                metadata, new SqlScriptSplitter(), new SqlStatementClassifier(), new ExecutionGuard(),
                new SqlExecutionRegistry(), dataEdit,
                new SqlExecutionMetrics()
        );
        return new Fixture(sql, url);
    }

    @Test
    void issuesOneRowTokenPerRowForASingleTableSelectWithAPrimaryKey() throws Exception {
        SqlResult result = fixture(false).sql()
                .executePage(1L, "select id, name, city from customers", 0, 10, "admin", null, null, null);

        assertThat(result.edit()).isNotNull();
        assertThat(result.edit().editable()).isTrue();
        assertThat(result.edit().tableName()).isEqualToIgnoringCase("customers");
        assertThat(result.edit().keyColumns()).containsExactly("ID");
        assertThat(result.edit().rowKeyTokens()).hasSize(result.rows().size()).doesNotContainNull();
        assertThat(result.edit().reason()).isNull();
    }

    @Test
    void refusesWhenTheKeyColumnIsNotSelected() throws Exception {
        SqlResult result = fixture(false).sql()
                .executePage(1L, "select name, city from customers", 0, 10, "admin", null, null, null);

        assertThat(result.edit().editable()).isFalse();
        assertThat(result.edit().reason()).contains("缺少行定位字段").contains("ID");
        assertThat(result.edit().rowKeyTokens()).isEmpty();
    }

    @Test
    void refusesWhenTheTableHasNoStableRowIdentity() throws Exception {
        SqlResult result = fixture(false).sql()
                .executePage(1L, "select a, b from no_key", 0, 10, "admin", null, null, null);

        assertThat(result.edit().editable()).isFalse();
        assertThat(result.edit().reason()).contains("主键");
    }

    @Test
    void refusesAJoinBecauseTheResultIsNotFromOneTable() throws Exception {
        SqlResult result = fixture(false).sql().executePage(
                1L, "select c.id, o.id as order_id from customers c join orders o on o.customer_id = c.id",
                0, 10, "admin", null, null, null
        );

        assertThat(result.edit().editable()).isFalse();
        assertThat(result.edit().reason()).contains("不是来自单张表");
    }

    @Test
    void refusesWhenAColumnIsAnExpressionRatherThanATableColumn() throws Exception {
        // 表达式列没有对应的表字段，界面上的这一列改了也不知道该写回哪里。
        SqlResult result = fixture(false).sql()
                .executePage(1L, "select id, upper(name) as upper_name from customers", 0, 10, "admin", null, null, null);

        assertThat(result.edit().editable()).isFalse();
        assertThat(result.edit().reason()).contains("别名或表达式");
    }

    /**
     * 别名把「界面上的列名」和「表里的字段」拆开了，而 JDBC 元数据分辨不出这一点：
     * getColumnLabel 给的是别名，getTableName 不受别名影响。放行的话，主键 code 会从
     * nickname 那一列取值，定位到的可能是另一行。
     */
    @Test
    void refusesWhenTheProjectionSwapsColumnNamesWithAliases() throws Exception {
        SqlResult result = fixture(false).sql()
                .executePage(1L, "select name as city, city as name, id from customers", 0, 10, "admin", null, null, null);

        assertThat(result.edit().editable()).isFalse();
        assertThat(result.edit().reason()).contains("别名或表达式");
        assertThat(result.edit().rowKeyTokens()).isEmpty();
    }

    @Test
    void refusesASingleAliasEvenWhenTheAliasIsHarmless() throws Exception {
        SqlResult result = fixture(false).sql()
                .executePage(1L, "select id, name as who from customers", 0, 10, "admin", null, null, null);

        assertThat(result.edit().editable()).isFalse();
    }

    @Test
    void stillAllowsQualifiedAndStarProjections() throws Exception {
        SqlResult star = fixture(false).sql()
                .executePage(1L, "select * from customers", 0, 10, "admin", null, null, null);
        assertThat(star.edit().editable()).isTrue();

        SqlResult qualified = fixture(false).sql()
                .executePage(1L, "select c.id, c.name from customers c order by c.id", 0, 10, "admin", null, null, null);
        assertThat(qualified.edit().editable()).isTrue();
    }

    @Test
    void refusesOnAReadonlyConnection() throws Exception {
        SqlResult result = fixture(true).sql()
                .executePage(1L, "select id, name from customers", 0, 10, "admin", null, null, null);

        assertThat(result.edit().editable()).isFalse();
        assertThat(result.edit().reason()).contains("只读");
    }

    @Test
    void tokensAreDistinctPerRowSoTheyCannotBeSwapped() throws Exception {
        SqlResult result = fixture(false).sql()
                .executePage(1L, "select id, name from customers", 0, 10, "admin", null, null, null);

        assertThat(result.edit().rowKeyTokens()).doesNotHaveDuplicates();
    }
}
