package com.example.dbadmin.access;

import java.util.List;

public record ConnectionAccessPolicy(
        long connectionId,
        String accessMode,
        Long ownerUserId,
        List<Grant> grants
) {
    public record Grant(String granteeType, long granteeId, ConnectionPermission permission) {}
}
