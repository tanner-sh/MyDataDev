package com.example.dbadmin.service.ai;

import java.util.List;

/** 一条连接上的业务用语与真实数据库对象映射。 */
public record AiBusinessTerm(
        long id,
        long connectionId,
        String term,
        List<String> aliases,
        List<String> objectNames,
        String description
) {
    public AiBusinessTerm {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        objectNames = objectNames == null ? List.of() : List.copyOf(objectNames);
    }
}
