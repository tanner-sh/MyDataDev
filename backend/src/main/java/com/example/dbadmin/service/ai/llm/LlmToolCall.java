package com.example.dbadmin.service.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** 模型请求调用一个受控工具。 */
public record LlmToolCall(String id, String name, JsonNode arguments) {
    public LlmToolCall {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("工具调用 id 不能为空。");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("工具名不能为空。");
        arguments = arguments == null || !arguments.isObject() ? JsonNodeFactory.instance.objectNode() : arguments;
    }
}
