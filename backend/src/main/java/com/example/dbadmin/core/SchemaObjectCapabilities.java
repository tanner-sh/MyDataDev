package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SchemaObjectCapability;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.example.dbadmin.core.SchemaObjectKind.*;
import static com.example.dbadmin.core.SchemaObjectOperation.*;

public final class SchemaObjectCapabilities {
    private static final EnumSet<SchemaObjectOperation> BASE = EnumSet.of(LIST, DETAIL, SOURCE, CREATE, DROP);

    private SchemaObjectCapabilities() {
    }

    public static List<SchemaObjectCapability> h2() {
        return capabilities(
                capability(VIEW, REPLACE),
                capability(SEQUENCE),
                capability(TRIGGER),
                capability(FUNCTION, INVOKE)
        );
    }

    public static List<SchemaObjectCapability> mysql() {
        return capabilities(
                capability(VIEW, REPLACE),
                capability(TRIGGER),
                capability(PROCEDURE, INVOKE),
                capability(FUNCTION, INVOKE)
        );
    }

    public static List<SchemaObjectCapability> mariaDb() {
        return capabilities(
                capability(VIEW, REPLACE),
                capability(SEQUENCE),
                capability(TRIGGER, REPLACE),
                capability(PROCEDURE, REPLACE, INVOKE),
                capability(FUNCTION, REPLACE, INVOKE)
        );
    }

    public static List<SchemaObjectCapability> postgresql() {
        return capabilities(
                capability(VIEW, REPLACE, DEPENDENCIES),
                capability(MATERIALIZED_VIEW, REFRESH, DEPENDENCIES),
                capability(SEQUENCE, DEPENDENCIES),
                capability(TRIGGER, REPLACE, ENABLE, DISABLE, DEPENDENCIES),
                capability(PROCEDURE, REPLACE, INVOKE, DEPENDENCIES),
                capability(FUNCTION, REPLACE, INVOKE, DEPENDENCIES)
        );
    }

    public static List<SchemaObjectCapability> oracleFamily() {
        return capabilities(
                capability(VIEW, REPLACE, DEPENDENCIES),
                capability(MATERIALIZED_VIEW, REFRESH, DEPENDENCIES),
                capability(SEQUENCE, DEPENDENCIES),
                capability(TRIGGER, REPLACE, ENABLE, DISABLE, DEPENDENCIES),
                capability(PROCEDURE, REPLACE, INVOKE, DEPENDENCIES),
                capability(FUNCTION, REPLACE, INVOKE, DEPENDENCIES)
        );
    }

    public static List<SchemaObjectCapability> oceanBaseMySql() {
        return mysql();
    }

    public static List<SchemaObjectCapability> sqlServer() {
        return capabilities(
                capability(VIEW, REPLACE, DEPENDENCIES),
                capability(SEQUENCE, DEPENDENCIES),
                capability(TRIGGER, REPLACE, ENABLE, DISABLE, DEPENDENCIES),
                capability(PROCEDURE, REPLACE, INVOKE, DEPENDENCIES),
                capability(FUNCTION, REPLACE, INVOKE, DEPENDENCIES)
        );
    }

    public static List<SchemaObjectCapability> sqlite() {
        return capabilities(capability(VIEW), capability(TRIGGER));
    }

    public static List<SchemaObjectCapability> clickHouse() {
        return capabilities(
                capability(VIEW, REPLACE),
                capability(MATERIALIZED_VIEW, REFRESH),
                capability(FUNCTION, REPLACE, INVOKE)
        );
    }

    private static SchemaObjectCapability capability(SchemaObjectKind kind, SchemaObjectOperation... extra) {
        EnumSet<SchemaObjectOperation> operations = EnumSet.copyOf(BASE);
        operations.addAll(List.of(extra));
        return new SchemaObjectCapability(kind.name(), operations.stream().map(Enum::name).toList());
    }

    private static List<SchemaObjectCapability> capabilities(SchemaObjectCapability... capabilities) {
        return List.copyOf(new ArrayList<>(List.of(capabilities)));
    }
}
