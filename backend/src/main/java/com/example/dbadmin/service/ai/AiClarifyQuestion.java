package com.example.dbadmin.service.ai;

import java.util.List;

/**
 * 一次反问：一个问题，外加零到五个可点的选项。
 *
 * @param options 可能为空 —— 「这个订单号是多少」给不出选项，强行要求只会让模型编造
 */
public record AiClarifyQuestion(String question, List<Option> options) {
    public AiClarifyQuestion {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** @param detail 可选：选这个意味着什么，界面上作为副标题 */
    public record Option(String label, String detail) {
    }
}
