package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.TableRelationDecisionRequest;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.repo.TableRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TableRelationServiceTest {
    private JdbcTemplate jdbc;
    private AuditRepository audit;
    private TableRelationService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:relation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO db_connection(id, name, db_type, jdbc_url) VALUES (1, 'fba', 'oracle', 'jdbc:oracle:thin:@x')");
        jdbc.update("INSERT INTO db_connection(id, name, db_type, jdbc_url) VALUES (2, 'other', 'oracle', 'jdbc:oracle:thin:@y')");
        audit = mock(AuditRepository.class);
        ConnectionService connections = mock(ConnectionService.class);
        service = new TableRelationService(connections, new SqlHistoryRepository(jdbc), new TableRelationRepository(jdbc), audit);
    }

    private void history(long connectionId, String status, String sql) {
        jdbc.update("INSERT INTO sql_history(connection_id, sql_text, sql_type, status, actor) VALUES (?, ?, 'QUERY', ?, 'someone')", connectionId, sql, status);
    }

    @Test
    void minesJoinsFromSuccessfulHistoryOfThisConnectionOnly() {
        history(1, "SUCCESS", "select * from gl_voucher v join org_orgs o on o.pk_org = v.pk_org where v.code = '13800000000'");
        history(1, "SUCCESS", "SELECT * FROM GL_VOUCHER A, ORG_ORGS B WHERE A.PK_ORG = B.PK_ORG");
        history(1, "SUCCESS", "select * from gl_detail d join gl_voucher v on d.pk_voucher = v.pk_voucher");
        history(1, "FAILED", "select * from a join b on a.x = b.y");
        history(2, "SUCCESS", "select * from c join d on c.k = d.k");

        var view = service.view(1, false);
        assertThat(view.scannedStatements()).isEqualTo(3);
        assertThat(view.mined()).extracting(pair -> pair.leftTable().toLowerCase() + "." + pair.leftColumn().toLowerCase()
                        + "=" + pair.rightTable().toLowerCase() + "." + pair.rightColumn().toLowerCase() + "×" + pair.hits())
                .containsExactly("gl_voucher.pk_org=org_orgs.pk_org×2", "gl_detail.pk_voucher=gl_voucher.pk_voucher×1");
        // 只有名字和次数：响应里不出现任何字面量。
        assertThat(view.toString()).doesNotContain("13800000000");
    }

    @Test
    void decisionsHideCandidatesAreKeyedRegardlessOfOrderAndAreAudited() {
        history(1, "SUCCESS", "select * from gl_voucher v join org_orgs o on o.pk_org = v.pk_org");
        history(1, "SUCCESS", "select * from gl_detail d join gl_voucher v on d.pk_voucher = v.pk_voucher");

        // 写反方向、换大小写，仍是同一对关联。
        var afterConfirm = service.decide(1, new TableRelationDecisionRequest("ORG_ORGS", "PK_ORG", "GL_VOUCHER", "PK_ORG", "confirmed", "history"), "alice");
        assertThat(afterConfirm.decided()).singleElement().satisfies(relation -> {
            assertThat(relation.status()).isEqualTo("CONFIRMED");
            assertThat(relation.source()).isEqualTo("HISTORY");
            assertThat(relation.leftTable()).isEqualTo("GL_VOUCHER");
            assertThat(relation.decidedBy()).isEqualTo("alice");
        });
        assertThat(afterConfirm.mined()).extracting(pair -> pair.leftTable()).containsExactly("gl_detail");
        verify(audit).onConnection(eq("alice"), eq("TABLE_RELATION_DECIDE"), eq(1L), contains("PK_ORG"), contains("CONFIRMED"));

        var afterReject = service.decide(1, new TableRelationDecisionRequest("gl_voucher", "pk_org", "org_orgs", "pk_org", "REJECTED", null), "bob");
        assertThat(afterReject.decided()).singleElement().satisfies(relation -> assertThat(relation.status()).isEqualTo("REJECTED"));

        var afterRemove = service.remove(1, new TableRelationDecisionRequest("org_orgs", "pk_org", "gl_voucher", "pk_org", null, null), "bob");
        assertThat(afterRemove.decided()).isEmpty();
        assertThat(afterRemove.mined()).hasSize(2);
        verify(audit).onConnection(eq("bob"), eq("TABLE_RELATION_REMOVE"), eq(1L), contains("pk_org"), eq("removed"));
        assertThat(service.view(2, false).decided()).isEmpty();
    }

    @Test
    void rejectsMalformedNamesAndUnknownStatuses() {
        assertThatThrownBy(() -> service.decide(1, new TableRelationDecisionRequest("fba.gl_voucher", "pk_org", "org_orgs", "pk_org", null, null), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Schema");
        assertThatThrownBy(() -> service.decide(1, new TableRelationDecisionRequest("t", "c", "t", "C", null, null), "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.decide(1, new TableRelationDecisionRequest("a", "x", "b", "y", "MAYBE", null), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MAYBE");
    }
}
