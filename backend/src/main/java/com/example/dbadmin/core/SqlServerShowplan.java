package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.SqlResult;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Streams the estimated plan; never resolves external XML resources. */
final class SqlServerShowplan {
    private SqlServerShowplan() {}

    static SqlResult read(Reader reader, int maxRows, long elapsedMs) throws Exception {
        if (reader == null) throw new IllegalArgumentException("SQL Server 返回了空执行计划。");
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new javax.xml.stream.XMLStreamException("执行计划不允许引用外部资源。");
        });
        var xml = factory.createXMLStreamReader(new java.io.FilterReader(reader) {
            private int count;
            @Override public int read(char[] buffer, int offset, int length) throws java.io.IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0 && (count += n) > 5_000_000) throw new java.io.IOException("执行计划超过 5 MB，请缩小查询范围。");
                return n;
            }
        });
        List<List<Object>> rows = new ArrayList<>();
        var stack = new ArrayDeque<String>();
        int statement = 0;
        String statementType = "";
        int limit = Math.max(1, maxRows);
        boolean truncated = false;
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.DTD) throw new IllegalArgumentException("执行计划不允许包含 DTD。");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("StmtSimple".equals(xml.getLocalName())) { statement++; statementType = attribute(xml, "StatementType"); }
                    if ("RelOp".equals(xml.getLocalName())) {
                        String id = attribute(xml, "NodeId");
                        if (rows.size() == limit) { truncated = true; break; }
                        rows.add(List.of(statement, id, stack.isEmpty() ? "" : stack.peek(),
                                attribute(xml, "PhysicalOp"), attribute(xml, "LogicalOp"),
                                attribute(xml, "EstimateRows"), attribute(xml, "EstimatedTotalSubtreeCost")));
                        stack.push(id);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "RelOp".equals(xml.getLocalName()) && !stack.isEmpty()) {
                    stack.pop();
                }
            }
        } finally { xml.close(); }
        if (rows.isEmpty()) {
            if (statement == 0) throw new IllegalArgumentException("执行计划中没有可显示的语句。");
            rows.add(List.of(statement, "", "", "无查询算子", statementType, "", ""));
        }
        var columns = List.of("Statement", "NodeId", "ParentNodeId", "PhysicalOp", "LogicalOp", "EstimateRows", "EstimatedTotalSubtreeCost")
                .stream().map(name -> new ResultColumn(name, name, "VARCHAR")).toList();
        return new SqlResult(columns, rows, -1, elapsedMs, true, limit, truncated);
    }

    private static String attribute(javax.xml.stream.XMLStreamReader xml, String name) {
        String value = xml.getAttributeValue(null, name);
        return value == null ? "" : value;
    }
}
