package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.ApiDtos.SqlSnippetRequest;
import com.example.dbadmin.dto.ApiDtos.SqlSnippetResponse;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlSnippetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SqlSnippetServiceTest {
    private SqlSnippetService service;
    private AuditRepository audit;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:snippet-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        audit = mock(AuditRepository.class);
        service = new SqlSnippetService(new SqlSnippetRepository(new JdbcTemplate(dataSource)), audit);
    }

    private SqlSnippetRequest request(String name, String sql, String dbType) {
        return new SqlSnippetRequest(name, "对账用", sql, dbType, "对账,日常");
    }

    @Test
    void savesAndListsASnippet() {
        SqlSnippetResponse created = service.create(request("每日对账", "select * from orders", null), "admin");

        assertThat(created.id()).isPositive();
        assertThat(created.name()).isEqualTo("每日对账");
        assertThat(service.list(null, null)).hasSize(1);
        verify(audit).global(anyString(), org.mockito.ArgumentMatchers.eq("SQL_SNIPPET_CREATE"), anyString(), anyString());
    }

    @Test
    void rejectsADuplicateNameWithAClearCode() {
        service.create(request("每日对账", "select 1", null), "admin");

        assertThatThrownBy(() -> service.create(request("每日对账", "select 2", null), "admin"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("SNIPPET_NAME_TAKEN"));
    }

    @Test
    void allowsRenamingASnippetToItsOwnName() {
        SqlSnippetResponse created = service.create(request("每日对账", "select 1", null), "admin");

        SqlSnippetResponse updated = service.update(created.id(), request("每日对账", "select 2", null), "admin");

        assertThat(updated.sql()).isEqualTo("select 2");
    }

    @Test
    void trimsTheNameAndRejectsABlankOne() {
        SqlSnippetResponse created = service.create(request("  留白  ", "select 1", null), "admin");
        assertThat(created.name()).isEqualTo("留白");

        assertThatThrownBy(() -> service.create(request("   ", "select 1", null), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersByDatabaseTypeButKeepsGenericSnippets() {
        service.create(request("通用查询", "select 1", null), "admin");
        service.create(request("MySQL 专用", "select 1 limit 1", "MySQL"), "admin");
        service.create(request("Oracle 专用", "select 1 from dual", "oracle"), "admin");

        // 类型大小写在写入时归一化，查询时不必再关心。
        assertThat(service.list(null, "mysql")).extracting("name")
                .containsExactlyInAnyOrder("通用查询", "MySQL 专用");
        assertThat(service.list(null, "oracle")).extracting("name")
                .containsExactlyInAnyOrder("通用查询", "Oracle 专用");
        assertThat(service.list(null, null)).hasSize(3);
    }

    @Test
    void searchesNameDescriptionSqlAndTags() {
        service.create(new SqlSnippetRequest("清理脚本", "删除过期数据", "delete from logs", null, "维护"), "admin");
        service.create(new SqlSnippetRequest("订单查询", "看看订单", "select * from orders", null, "日常"), "admin");

        assertThat(service.list("logs", null)).extracting("name").containsExactly("清理脚本");
        assertThat(service.list("维护", null)).extracting("name").containsExactly("清理脚本");
        assertThat(service.list("看看", null)).extracting("name").containsExactly("订单查询");
    }

    @Test
    void ordersFrequentlyUsedSnippetsFirst() {
        SqlSnippetResponse rare = service.create(request("少用", "select 1", null), "admin");
        SqlSnippetResponse common = service.create(request("常用", "select 2", null), "admin");

        service.recordUse(common.id());
        service.recordUse(common.id());
        service.recordUse(rare.id());

        assertThat(service.list(null, null)).extracting("name").containsExactly("常用", "少用");
        assertThat(service.list(null, null).get(0).useCount()).isEqualTo(2);
    }

    @Test
    void deletingRemovesItAndUnknownIdsAreRejected() {
        SqlSnippetResponse created = service.create(request("待删除", "select 1", null), "admin");

        service.delete(created.id(), "admin");

        assertThat(service.list(null, null)).isEmpty();
        assertThatThrownBy(() -> service.delete(created.id(), "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordUse(created.id())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void treatsLikeMetacharactersInTheSearchAsLiterals() {
        service.create(request("折扣 100%", "select 1", null), "admin");
        service.create(request("普通", "select 2", null), "admin");

        assertThat(service.list("100%", null)).hasSize(1);
    }
}
