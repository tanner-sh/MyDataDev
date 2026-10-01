package com.example.dbadmin.service;

import org.springframework.stereotype.Component;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class SqlScriptSplitter {
    public List<StatementSegment> split(String script) { return split(script, "generic"); }
    public List<StatementSegment> split(String script, String dbType) {
        List<StatementSegment> result = new ArrayList<>();
        if (script == null || script.isBlank()) return result;
        try {
            SqlScriptParser.read(new StringReader(script), dbType, Math.max(1, script.length()), unit ->
                    result.add(new StatementSegment(unit.sql(), Math.toIntExact(unit.startOffset()), Math.toIntExact(unit.endOffset()))));
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
        return result;
    }
    public record StatementSegment(String sql, int startOffset, int endOffset) { }
}
