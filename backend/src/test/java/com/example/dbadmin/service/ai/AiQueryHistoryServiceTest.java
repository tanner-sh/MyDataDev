package com.example.dbadmin.service.ai;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.service.SqlStatementClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiQueryHistoryServiceTest {
    private final SqlStatementClassifier classifier = new SqlStatementClassifier();

    private static SqlHistoryResponse row(long id, String sql, String status) {
        return new SqlHistoryResponse(id, 3, sql, "EXECUTE", status, 12, null, "tanner", "2026-09-0" + (id % 9 + 1));
    }

    @Test
    void rendersQueryShapesWithoutAnyBusinessValue() {
        var results = AiQueryHistoryService.rank(
                List.of(row(1, "SELECT CUST_NM FROM T_CRM_0021 WHERE MOBILE = '13800138000'", "SUCCESS")),
                classifier, Set.of("T_CRM_0021"), null, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).sql()).isEqualTo("SELECT CUST_NM FROM T_CRM_0021 WHERE MOBILE = ?");
        assertThat(results.get(0).tables()).containsExactly("T_CRM_0021");
    }

    @Test
    void skipsFailedStatementsAndAnythingThatIsNotAReadQuery() {
        var results = AiQueryHistoryService.rank(List.of(
                row(1, "SELECT * FROM T_CRM_0021 WHERE ID = 1", "FAILED"),
                row(2, "DELETE FROM T_CRM_0021 WHERE ID = 2", "SUCCESS"),
                row(3, "SHOW TABLES", "SUCCESS"),
                row(4, "SELECT CUST_NM FROM T_CRM_0021", "SUCCESS")
        ), classifier, Set.of("T_CRM_0021"), null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).sql()).isEqualTo("SELECT CUST_NM FROM T_CRM_0021");
    }

    @Test
    void collapsesTheSameQueryShapeAndCountsHowOftenItRan() {
        var results = AiQueryHistoryService.rank(List.of(
                row(1, "SELECT ID FROM SALES_ORDER WHERE ORDER_NO = 'SO-1'", "SUCCESS"),
                row(2, "select id from sales_order where order_no = 'SO-2'", "SUCCESS"),
                row(3, "SELECT ID   FROM SALES_ORDER\n WHERE ORDER_NO = 'SO-3'", "SUCCESS")
        ), classifier, Set.of("SALES_ORDER"), null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).runs()).isEqualTo(3);
    }

    /** 找相似写法时，用到同样几张表几乎总比文本里撞上同一个词更说明问题。 */
    @Test
    void ranksQueriesThatCoverEveryRequestedTableAboveKeywordMatches() {
        var results = AiQueryHistoryService.rank(List.of(
                row(1, "SELECT c.CUST_NM FROM T_CRM_0021 c", "SUCCESS"),
                row(2, "SELECT o.TOTAL_AMOUNT FROM SALES_ORDER o JOIN T_CRM_0021 c ON c.ID = o.CUSTOMER_ID", "SUCCESS"),
                row(3, "SELECT p.PRODUCT_NAME FROM PRODUCT p WHERE p.PRODUCT_NAME LIKE '%TOTAL_AMOUNT%'", "SUCCESS")
        ), classifier, Set.of("SALES_ORDER", "T_CRM_0021"), "TOTAL_AMOUNT", 10);

        assertThat(results.get(0).sql()).contains("JOIN T_CRM_0021");
        assertThat(results).extracting(AiQueryHistoryService.HistoryQuery::sql)
                .noneMatch(sql -> sql.contains("PRODUCT p WHERE"));
    }

    @Test
    void returnsNothingWhenNoHistoryTouchesTheRequestedTables() {
        var results = AiQueryHistoryService.rank(
                List.of(row(1, "SELECT * FROM PAYMENT", "SUCCESS")),
                classifier, Set.of("T_CRM_0021"), null, 10);

        assertThat(results).isEmpty();
    }

    @Test
    void fallsBackToRecentQueriesWhenNoFilterIsGiven() {
        var results = AiQueryHistoryService.rank(List.of(
                row(1, "SELECT * FROM PAYMENT", "SUCCESS"),
                row(2, "SELECT * FROM PRODUCT", "SUCCESS")
        ), classifier, Set.of(), null, 10);

        assertThat(results).hasSize(2);
    }

    @Test
    void honoursTheResultLimit() {
        var rows = new java.util.ArrayList<SqlHistoryResponse>();
        for (int index = 0; index < 20; index++) {
            rows.add(row(index, "SELECT C" + index + " FROM SALES_ORDER", "SUCCESS"));
        }

        assertThat(AiQueryHistoryService.rank(rows, classifier, Set.of("SALES_ORDER"), null, 4)).hasSize(4);
    }
}
