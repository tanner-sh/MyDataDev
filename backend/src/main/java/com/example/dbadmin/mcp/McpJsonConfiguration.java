package com.example.dbadmin.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 替换 Spring AI 自带的 {@code mcpServerObjectMapper}。
 *
 * <p>自带的那个用 {@code serializationInclusion(NON_NULL)}，而 Jackson 的这个开关同时作用于 Map 的条目：
 * 工具返回值先被转成 Map 放进 {@code structuredContent}，于是所有值为 null 的字段在线上整键消失。
 * 输出 schema 却把这些字段声明为「必有、可为 null」，严格校验的客户端每次都会拒收整个结果 ——
 * {@code db_execute_script} 成功时 failedStartLine 等字段为 null，调用方因此永远拿不到 taskId。</p>
 *
 * <p>这里只把 Map 条目改回原样输出；协议对象自身的属性仍然省略 null，与 SDK 的线上格式一致。</p>
 */
@Configuration(proxyBeanMethods = false)
public class McpJsonConfiguration {

    @Bean(name = "mcpServerObjectMapper", defaultCandidate = false)
    public ObjectMapper mcpServerObjectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .addModules(JacksonUtils.instantiateAvailableModules())
                .build();
    }
}
