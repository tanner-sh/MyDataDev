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
        String dollarQuote = null;
        char oracleQuoteEnd = '\0';
        int statementStart = 0;

        for (int i = 0; i < script.length(); i++) {
            char current = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            if (dollarQuote != null) {
                if (script.startsWith(dollarQuote, i)) {
                    i += dollarQuote.length() - 1;
                    dollarQuote = null;
                }
                continue;
            }
            if (oracleQuoteEnd != '\0') {
                if (current == oracleQuoteEnd && next == '\'') {
                    oracleQuoteEnd = '\0';
                    i++;
                }
                continue;
            }

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
            } else if ((current == 'q' || current == 'Q') && next == '\'' && i + 2 < script.length()) {
                oracleQuoteEnd = matchingOracleQuote(script.charAt(i + 2));
                i += 2;
            } else if (current == '$') {
                String delimiter = dollarDelimiter(script, i);
                if (delimiter != null) {
                    dollarQuote = delimiter;
                    i += delimiter.length() - 1;
                }
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
        if (trimmedStart < trimmedEnd && hasSqlContent(script, trimmedStart, trimmedEnd)) {
            segments.add(new StatementSegment(script.substring(trimmedStart, trimmedEnd), trimmedStart, trimmedEnd));
        }
    }

    private char matchingOracleQuote(char opening) {
        return switch (opening) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> opening;
        };
    }

    private String dollarDelimiter(String script, int index) {
        int end = index + 1;
        while (end < script.length() && (Character.isLetterOrDigit(script.charAt(end)) || script.charAt(end) == '_')) end++;
        String tag = script.substring(index + 1, end);
        boolean validTag = tag.isEmpty() || (Character.isLetter(tag.charAt(0)) || tag.charAt(0) == '_');
        return validTag && end < script.length() && script.charAt(end) == '$' ? script.substring(index, end + 1) : null;
    }

    private boolean hasSqlContent(String script, int start, int end) {
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = start; index < end; index++) {
            char current = script.charAt(index);
            char next = index + 1 < end ? script.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (!Character.isWhitespace(current)) {
                return true;
            }
        }
        return false;
    }

    public record StatementSegment(String sql, int startOffset, int endOffset) {
    }
}
