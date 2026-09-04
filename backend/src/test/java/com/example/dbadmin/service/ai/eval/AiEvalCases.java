package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.service.ai.AiBusinessTerm;

import java.util.List;

/**
 * 固定的评测用例集。
 *
 * <p>这些用例是基线，不是测试断言 —— 改一条就意味着历史分数不可比。要加覆盖面就加新用例，
 * 不要改已有的。</p>
 */
public final class AiEvalCases {
    private AiEvalCases() {
    }

    /** 管理员会为这条连接配的业务词典。第一条用例专门用来验证它有没有起作用。 */
    public static List<AiBusinessTerm> glossary(long connectionId) {
        return List.of(
                new AiBusinessTerm(1, connectionId, "客户",
                        List.of("会员", "买家", "customer"), List.of("T_CRM_0021"), "客户主档，物理表名无语义"),
                new AiBusinessTerm(2, connectionId, "销售员",
                        List.of("业务员", "客户经理"), List.of("APP_USER"), "销售员就是系统用户表里的一行"),
                new AiBusinessTerm(3, connectionId, "客单价",
                        List.of("平均订单金额"), List.of("SALES_ORDER"), "客单价 = 订单总金额 / 订单数"));
    }

    public static List<AiEvalCase> all() {
        return List.of(
                AiEvalCase.of("crm-lookup",
                        "查询所有启用状态的客户名称和手机号",
                        List.of("T_CRM_0021"),
                        "物理表名无语义，只能靠表注释或业务词典找到"),
                AiEvalCase.of("customer-revenue",
                        "统计最近 30 天每个客户的订单总金额，按金额从高到低排列",
                        List.of("T_CRM_0021", "SALES_ORDER"),
                        "要沿外键从订单回到客户，并读懂 ORDER_DATE 而不是 CREATED_AT"),
                AiEvalCase.of("category-count",
                        "每个商品类目下有多少个商品",
                        List.of("PRODUCT", "PRODUCT_CATEGORY"),
                        "最简单的一跳外键聚合"),
                AiEvalCase.of("order-items",
                        "订单号 SO-2026-001 里买了哪些商品，各多少件",
                        List.of("SALES_ORDER", "SALES_ORDER_ITEM", "PRODUCT"),
                        "两跳外键，且过滤条件在订单号而不是订单 ID 上"),
                AiEvalCase.of("unpaid-orders",
                        "哪些订单已经下单但还没有任何支付记录",
                        List.of("SALES_ORDER", "PAYMENT"),
                        "需要 LEFT JOIN 或 NOT EXISTS，不是普通内连接"),
                AiEvalCase.of("rep-monthly",
                        "统计每个销售员本月成交的订单数",
                        List.of("SALES_ORDER", "APP_USER"),
                        List.of("APP_USER_ARCHIVE"),
                        "APP_USER_ARCHIVE 字段几乎一样，是最容易选错的干扰项"),
                AiEvalCase.of("top-customers",
                        "客单价最高的前 10 个客户",
                        List.of("T_CRM_0021", "SALES_ORDER"),
                        "客单价的口径在业务词典里，模型要用它而不是自己发明"),
                AiEvalCase.of("no-repeat",
                        "找出下过单但从来没有复购的客户",
                        List.of("T_CRM_0021", "SALES_ORDER"),
                        "需要按客户分组后再过滤计数"),
                AiEvalCase.of("category-revenue",
                        "每个商品类目的销售额是多少",
                        List.of("PRODUCT_CATEGORY", "PRODUCT", "SALES_ORDER_ITEM"),
                        "金额在明细表的 AMT 上，不在商品的 LIST_PRICE 上"),
                AiEvalCase.of("big-orders",
                        "列出金额超过一万的订单，带上客户名称和销售员姓名",
                        List.of("SALES_ORDER", "T_CRM_0021", "APP_USER"),
                        List.of("APP_USER_ARCHIVE"),
                        "三表连接，两个外键指向不同的表"));
    }
}
