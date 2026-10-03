package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.assertj.core.api.Assertions.*;

class SqlServerShowplanTest {
    private static final String PLAN = """
        <ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan">
          <BatchSequence><Batch><Statements><StmtSimple StatementType="SELECT">
            <QueryPlan><RelOp NodeId="0" PhysicalOp="Nested Loops" LogicalOp="Inner Join" EstimateRows="2" EstimatedTotalSubtreeCost="0.03">
              <NestedLoops><RelOp NodeId="1" PhysicalOp="Index Seek" LogicalOp="Index Seek" EstimateRows="1"/>
                <RelOp NodeId="2" PhysicalOp="Table Scan" LogicalOp="Table Scan" EstimateRows="2"/>
              </NestedLoops>
            </RelOp></QueryPlan>
          </StmtSimple></Statements></Batch></BatchSequence>
        </ShowPlanXML>
        """;
    @Test void preservesTreeAndEstimates() throws Exception {
        var result = SqlServerShowplan.read(new StringReader(PLAN), 10, 12);
        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(0)).containsExactly(1, "0", "", "Nested Loops", "Inner Join", "2", "0.03");
        assertThat(result.rows().get(1).get(2)).isEqualTo("0");
        assertThat(result.rows().get(2).get(2)).isEqualTo("0");
        assertThat(result.truncated()).isFalse();
    }
    @Test void capsOperatorsAndHandlesPlansWithoutQueryOperators() throws Exception {
        assertThat(SqlServerShowplan.read(new StringReader(PLAN), 1, 0).truncated()).isTrue();
        var simple = SqlServerShowplan.read(new StringReader("<ShowPlanXML><StmtSimple StatementType='SELECT WITHOUT QUERY'/></ShowPlanXML>"), 1, 0);
        assertThat(simple.rows().get(0)).contains("无查询算子", "SELECT WITHOUT QUERY");
    }
    @Test void rejectsExternalEntitiesAndOversizedPlans() {
        assertThatThrownBy(() -> SqlServerShowplan.read(new StringReader("<!DOCTYPE plan [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><plan>&secret;</plan>"), 10, 0))
                .hasMessageContaining("DTD");
        assertThatThrownBy(() -> SqlServerShowplan.read(new StringReader("<ShowPlanXML>" + " ".repeat(5_000_001) + "</ShowPlanXML>"), 10, 0))
                .hasMessageContaining("5 MB");
    }
}
