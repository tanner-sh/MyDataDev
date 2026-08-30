package com.example.dbadmin.access;

import java.time.Instant;
import java.util.List;

public record UserGroup(
        long id,
        String name,
        String description,
        List<Long> memberUserIds,
        Instant createdAt,
        Instant updatedAt
) {}
