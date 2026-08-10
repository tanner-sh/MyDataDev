package com.example.dbadmin.service;

public record SqlQueryLimits(
        int defaultRows,
        int maxRows,
        int maxCells,
        long maxTextChars,
        int maxCellTextChars,
        int timeoutSeconds
) {
    public SqlQueryLimits {
        defaultRows = Math.max(1, defaultRows);
        maxRows = Math.max(defaultRows, maxRows);
        maxCells = Math.max(1, maxCells);
        maxTextChars = Math.max(1, maxTextChars);
        maxCellTextChars = Math.max(1, maxCellTextChars);
        timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public int normalizeRows(Integer requestedRows) {
        int requested = requestedRows == null ? defaultRows : requestedRows;
        return Math.min(Math.max(1, requested), maxRows);
    }
}
