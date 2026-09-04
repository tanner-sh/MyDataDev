package com.example.dbadmin.service.ai.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Provider 无关的函数工具定义，inputSchema 使用 JSON Schema object。 */
public record LlmToolDefinition(String name, String description, ObjectNode inputSchema) {
    public LlmToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("工具名不能为空。");
        description = description == null ? "" : description;
        if (inputSchema == null) throw new IllegalArgumentException("工具参数 schema 不能为空。");
    }
}
