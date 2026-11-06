package com.example.dbadmin.service;

import com.example.dbadmin.service.SchemaExportOrdering.Edge;
import com.example.dbadmin.service.SchemaExportOrdering.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaExportOrderingTest {
    private static Node node(String kind, String name) {
        return new Node(kind + ":" + name, kind, name);
    }

    private static List<String> names(SchemaExportOrdering.Result result) {
        return result.ordered().stream().map(Node::name).toList();
    }

    @Test
    void putsReferencedTablesFirstEvenWhenTheNameSortsLater() {
        // 按名字排 A_ORDER_ITEM 会在最前，而它的外键指向 Z_ORDER，Z_ORDER 又指向 M_CUSTOMER。
        var result = SchemaExportOrdering.order(
                List.of(node("TABLE", "A_ORDER_ITEM"), node("TABLE", "Z_ORDER"), node("TABLE", "M_CUSTOMER")),
                List.of(new Edge("TABLE:A_ORDER_ITEM", "TABLE:Z_ORDER"), new Edge("TABLE:Z_ORDER", "TABLE:M_CUSTOMER")));
        assertThat(names(result)).containsExactly("M_CUSTOMER", "Z_ORDER", "A_ORDER_ITEM");
        assertThat(result.dependsOn().get("TABLE:A_ORDER_ITEM")).containsExactly("TABLE:Z_ORDER");
        assertThat(result.cycles()).isEmpty();
    }

    @Test
    void usesTheDefaultKindOrderWhenNoEdgeSaysOtherwise() {
        var result = SchemaExportOrdering.order(List.of(
                node("TRIGGER", "TRG"), node("PROCEDURE", "P"), node("VIEW", "V"), node("FUNCTION", "F"),
                node("TABLE", "T"), node("SEQUENCE", "S"), node("MATERIALIZED_VIEW", "MV")), List.of());
        assertThat(names(result)).containsExactly("S", "T", "F", "V", "MV", "P", "TRG");
    }

    @Test
    void anEdgeOverridesTheKindOrder() {
        // 视图引用视图：按名字 A_SUMMARY 在前，可它依赖 B_ACTIVE；函数也可能读一个视图。
        var result = SchemaExportOrdering.order(
                List.of(node("VIEW", "A_SUMMARY"), node("VIEW", "B_ACTIVE"), node("FUNCTION", "F_TOTAL"), node("TABLE", "T")),
                List.of(new Edge("VIEW:A_SUMMARY", "VIEW:B_ACTIVE"), new Edge("VIEW:B_ACTIVE", "TABLE:T"),
                        new Edge("FUNCTION:F_TOTAL", "VIEW:A_SUMMARY")));
        assertThat(names(result)).containsExactly("T", "B_ACTIVE", "A_SUMMARY", "F_TOTAL");
    }

    @Test
    void reportsCyclesButStillOutputsEveryObject() {
        var result = SchemaExportOrdering.order(
                List.of(node("TABLE", "DEPT"), node("TABLE", "EMP"), node("TABLE", "AUDIT_LOG"), node("VIEW", "V_EMP")),
                List.of(new Edge("TABLE:DEPT", "TABLE:EMP"), new Edge("TABLE:EMP", "TABLE:DEPT"),
                        new Edge("VIEW:V_EMP", "TABLE:EMP")));
        assertThat(names(result)).containsExactlyInAnyOrder("DEPT", "EMP", "AUDIT_LOG", "V_EMP");
        assertThat(names(result).indexOf("V_EMP")).isGreaterThan(names(result).indexOf("EMP"));
        assertThat(result.cycles()).containsExactly(List.of("DEPT", "EMP"));
    }

    @Test
    void ignoresSelfReferencesAndEdgesOutsideTheSelection() {
        var result = SchemaExportOrdering.order(
                List.of(node("TABLE", "TREE_NODE")),
                List.of(new Edge("TABLE:TREE_NODE", "TABLE:TREE_NODE"), new Edge("TABLE:TREE_NODE", "TABLE:ELSEWHERE")));
        assertThat(names(result)).containsExactly("TREE_NODE");
        assertThat(result.cycles()).isEmpty();
        assertThat(result.dependsOn().get("TABLE:TREE_NODE")).isEmpty();
    }
}
