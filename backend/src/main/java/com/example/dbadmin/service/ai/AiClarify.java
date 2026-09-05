package com.example.dbadmin.service.ai;

import com.example.dbadmin.service.ai.llm.LlmToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 反问：模型不确定口径时，问一句，而不是挑一个猜。
 *
 * <p>此前系统提示里就写着「找不到时明确询问用户」，但没有出口形状 —— 反问和答案一样是一段
 * 正文，前端只能当普通回答渲染，用户得自己把问题读出来再手打回答。更要命的是打分：评测集里
 * 每条用例的正确答案都是一条 SQL，猜一个总比问一句得分高，所以模型学到的就是别问。</p>
 *
 * <p>做成工具而不是约定一段 JSON 正文：函数调用是模型本来就在用的通道，参数由 provider 保证是
 * 结构化的；让它在正文里输出 JSON，则每一次格式抖动都要在这边写解析补丁。</p>
 *
 * <p>选项是可选的。「你指的是哪张客户表」能给出选项，「这个订单号是多少」给不出 —— 强行要求
 * 选项只会让模型编造几个。</p>
 */
public final class AiClarify {
    public static final String TOOL = "ask_user";
    /** 最多给几个选项。再多用户就不是在选，而是在读一份清单了。 */
    public static final int MAX_OPTIONS = 5;
    static final int MAX_QUESTION_CHARS = 300;
    static final int MAX_LABEL_CHARS = 60;
    static final int MAX_DETAIL_CHARS = 200;

    private AiClarify() {
    }

    public static LlmToolDefinition definition() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("question")
                .put("type", "string")
                .put("description", "要问用户的那一个问题，一句话。");
        ObjectNode options = properties.putObject("options");
        options.put("type", "array");
        options.put("description", "可选：给用户挑的几个答案，最多 5 个。给不出选项就不要编。");
        ObjectNode item = options.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("label").put("type", "string").put("description", "选项本身，尽量短");
        itemProperties.putObject("detail").put("type", "string").put("description", "可选：这个选项意味着什么");
        item.putArray("required").add("label");
        schema.putArray("required").add("question");
        schema.put("additionalProperties", false);
        return new LlmToolDefinition(TOOL,
                "需求有歧义、或者结构里有多个都说得通的对象时，问用户一句再动手。"
                        + "只在答案会改变最终 SQL 时才用；能靠工具查清楚的一律自己查。",
                schema);
    }

    /**
     * 解析模型给出的问题。
     *
     * <p>问题为空就当它没问 —— 一个没有问题的反问会让对话停在一个空气泡上，还不如让模型
     * 重来一次。</p>
     */
    public static Optional<AiClarifyQuestion> parse(JsonNode arguments) {
        if (arguments == null) return Optional.empty();
        String question = clamp(arguments.path("question").asText(""), MAX_QUESTION_CHARS);
        if (question.isBlank()) return Optional.empty();
        List<AiClarifyQuestion.Option> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : arguments.path("options")) {
            String label = clamp(node.path("label").asText(""), MAX_LABEL_CHARS);
            if (label.isBlank() || !seen.add(label.toLowerCase(Locale.ROOT))) continue;
            String detail = clamp(node.path("detail").asText(""), MAX_DETAIL_CHARS);
            options.add(new AiClarifyQuestion.Option(label, detail.isBlank() ? null : detail));
            if (options.size() >= MAX_OPTIONS) break;
        }
        return Optional.of(new AiClarifyQuestion(question, List.copyOf(options)));
    }

    private static String clamp(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
