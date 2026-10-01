package com.example.dbadmin.model;

public record SqlFileTransaction(String mode, String endOfFileAction, long controlCount, long opaqueCount,
                                 long commitCount, long rollbackCount, Long lastCommitIndex,
                                 String outcome, Integer failedStartLine, Integer failedEndLine) {
    public static SqlFileTransaction defaults() {
        return new SqlFileTransaction("BATCH", "COMMIT", 0, 0, 0, 0, null, "NOT_STARTED", null, null);
    }
}
