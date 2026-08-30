package com.example.dbadmin.access;

import java.time.Instant;
import java.util.List;

public record UserGroup(
        long id,
        String name,
        String description,
        List<Long> memberUserIds,
        /** memberUserIds 中由身份提供器同步而来的那部分：管理员改不动，只能去 IdP 里调整。 */
        List<Long> externalMemberUserIds,
        Instant createdAt,
        Instant updatedAt
) {}
