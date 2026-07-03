package com.example.dbadmin.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SqlScriptSplitter {
    public List<StatementSegment> split(String script) {
        List<StatementSegment> segments = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return segments;
        }

        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean backtickQuote = false;
        boolean bracketQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        int statementStart = 0;

        for (int i = 0; i < script.length(); i++) {
            char current = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (singleQuote) {
                if (current == '\'' && next == '\'') {
                    i++;
                    continue;
                }
                if (current == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                if (current == '"' && next == '"') {
                    i++;
                    continue;
                }
                if (current == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (backtickQuote) {
                if (current == '`' && next == '`') {
                    i++;
                    continue;
                }
                if (current == '`') {
                    backtickQuote = false;
                }
                continue;
            }
            if (bracketQuote) {
                if (current == ']' && next == ']') {
                    i++;
                    continue;
                }
                if (current == ']') {
                    bracketQuote = false;
                }
                continue;
            }

            if (current == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (current == '\'') {
                singleQuote = true;
            } else if (current == '"') {
                doubleQuote = true;
            } else if (current == '`') {
                backtickQuote = true;
            } else if (current == '[') {
                bracketQuote = true;
            } else if (current == ';') {
                addSegment(script, statementStart, i, segments);
                statementStart = i + 1;
            }
        }

        addSegment(script, statementStart, script.length(), segments);
        return segments;
    }

    private void addSegment(String script, int start, int end, List<StatementSegment> segments) {
        int trimmedStart = start;
        int trimmedEnd = end;
        while (trimmedStart < trimmedEnd && Character.isWhitespace(script.charAt(trimmedStart))) {
            trimmedStart++;
        }
        while (trimmedEnd > trimmedStart && Character.isWhitespace(script.charAt(trimmedEnd - 1))) {
            trimmedEnd--;
        }
        if (trimmedStart < trimmedEnd) {
            segments.add(new StatementSegment(script.substring(trimmedStart, trimmedEnd), trimmedStart, trimmedEnd));
        }
    }

    public record StatementSegment(String sql, int startOffset, int endOffset) {
    }
}
