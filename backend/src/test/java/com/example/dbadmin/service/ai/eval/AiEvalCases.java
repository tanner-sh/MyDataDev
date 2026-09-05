package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.service.ai.AiBusinessTerm;

import java.util.ArrayList;
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

    /**
     * 这条连接上「跑过」的查询。
     *
     * <p>刻意只放业务口径而不是覆盖全部用例：这个库的人统计成交只算 {@code ORDER_STATUS = 'PAID'}、
     * 销售额取明细的 {@code AMT} 而不是商品的 {@code LIST_PRICE}、统计销售员从来不碰归档表。这三条
     * 规矩在表结构和外键里一个字都没有，只有跑过的语句里有 —— 历史检索工具要证明的就是它能把
     * 这类知识带给模型。</p>
     */
    public static List<SqlHistoryResponse> queryHistory(long connectionId) {
        List<String> queries = List.of(
                "SELECT c.CUST_NM, SUM(o.TOTAL_AMOUNT) FROM SALES_ORDER o"
                        + " JOIN T_CRM_0021 c ON c.ID = o.CUSTOMER_ID"
                        + " WHERE o.ORDER_STATUS = 'PAID' AND o.ORDER_DATE >= '2026-08-01' GROUP BY c.CUST_NM",
                "SELECT u.DISPLAY_NAME, COUNT(*) FROM SALES_ORDER o"
                        + " JOIN APP_USER u ON u.ID = o.SALES_REP_ID"
                        + " WHERE o.ORDER_STATUS = 'PAID' AND u.ACTIVE = TRUE GROUP BY u.DISPLAY_NAME",
                "SELECT pc.CATEGORY_NAME, SUM(i.AMT) FROM SALES_ORDER_ITEM i"
                        + " JOIN PRODUCT p ON p.ID = i.PRODUCT_ID"
                        + " JOIN PRODUCT_CATEGORY pc ON pc.ID = p.CATEGORY_ID GROUP BY pc.CATEGORY_NAME",
                "SELECT o.ORDER_NO FROM SALES_ORDER o"
                        + " WHERE NOT EXISTS (SELECT 1 FROM PAYMENT p WHERE p.ORDER_ID = o.ID)",
                "SELECT CUST_NM, MOBILE FROM T_CRM_0021 WHERE ENABLED = TRUE ORDER BY CREATED_AT DESC");

        List<SqlHistoryResponse> rows = new ArrayList<>();
        long id = 1;
        for (String sql : queries) {
            // 每条都跑过好几次：常跑的写法就是这个库的惯例，检索时权重更高。
            for (int run = 0; run < 3; run++) {
                rows.add(new SqlHistoryResponse(id++, connectionId, sql, "EXECUTE", "SUCCESS",
                        40, null, "tanner", "2026-08-30"));
            }
        }
        return List.copyOf(rows);
    }

    public static List<AiEvalCase> all() {
        return List.of(
                AiEvalCase.of("crm-lookup",
                        "查询所有启用状态的客户名称和手机号",
                        List.of("T_CRM_0021"),
                        List.of("ENABLED"),
                        "物理表名无语义，只能靠表注释或业务词典找到"),
                AiEvalCase.of("customer-revenue",
                        "统计最近 30 天每个客户的订单总金额，按金额从高到低排列",
                        List.of("T_CRM_0021", "SALES_ORDER"),
                        List.of("TOTAL_AMOUNT", "ORDER_DATE"),
                        "要沿外键从订单回到客户，并读懂 ORDER_DATE 而不是 CREATED_AT"),
                AiEvalCase.of("category-count",
                        "每个商品类目下有多少个商品",
                        List.of("PRODUCT", "PRODUCT_CATEGORY"),
                        "最简单的一跳外键聚合"),
                AiEvalCase.of("order-items",
                        "订单号 SO-2026-001 里买了哪些商品，各多少件",
                        List.of("SALES_ORDER", "SALES_ORDER_ITEM", "PRODUCT"),
                        List.of("ORDER_NO", "QTY"),
                        "两跳外键，且过滤条件在订单号而不是订单 ID 上"),
                AiEvalCase.of("unpaid-orders",
                        "哪些订单已经下单但还没有任何支付记录",
                        List.of("SALES_ORDER", "PAYMENT"),
                        "需要 LEFT JOIN 或 NOT EXISTS，不是普通内连接"),
                AiEvalCase.of("rep-monthly",
                        "统计每个销售员本月成交的订单数",
                        List.of("SALES_ORDER", "APP_USER"),
                        List.of("APP_USER_ARCHIVE"),
                        List.of("ORDER_STATUS"),
                        "干扰项在 APP_USER_ARCHIVE；「成交」只算 PAID 这个口径只写在历史查询里"),
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
                        List.of("AMT"),
                        "金额在明细表的 AMT 上，不在商品的 LIST_PRICE 上"),
                AiEvalCase.of("big-orders",
                        "列出金额超过一万的订单，带上客户名称和销售员姓名",
                        List.of("SALES_ORDER", "T_CRM_0021", "APP_USER"),
                        List.of("APP_USER_ARCHIVE"),
                        List.of("TOTAL_AMOUNT"),
                        "三表连接，两个外键指向不同的表"),
                // 以下三条的正确答案是一个问题。没有它们，打分只奖励「猜出一条 SQL」——
                // 猜一个总比问一句得分高，模型学到的就是别问。
                AiEvalCase.clarify("ambiguous-user",
                        "帮我查一下用户",
                        "「用户」在这个库里同时指系统用户（APP_USER）和客户（T_CRM_0021），"
                                + "两张表都说得通，选哪张会改变整条 SQL"),
                AiEvalCase.clarify("ambiguous-amount",
                        "统计一下金额",
                        "既没说哪张表的金额（订单头 TOTAL_AMOUNT 还是明细 AMT），也没说按什么维度汇总"),
                AiEvalCase.clarify("ambiguous-active",
                        "谁最活跃",
                        "「谁」是销售员还是客户，「活跃」是下单次数、金额还是最近登录 —— 三个维度全是空的"));
    }
}
