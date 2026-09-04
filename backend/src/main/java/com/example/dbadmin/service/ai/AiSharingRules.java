package com.example.dbadmin.service.ai;

/**
 * 连接共享策略的规范化规则。
 *
 * <p>纯逻辑：什么档位允许发样本、样本上限是多少、生产连接禁哪一档。放在这里而不是
 * service 里，是因为这几条判断是整个 AI 功能对外承诺的隐私边界，值得单独测。</p>
 */
public final class AiSharingRules {
    /** 样本行数上限。再多也没有诊断价值，只是把更多真实数据送出网。 */
    public static final int MAX_SAMPLE_ROWS = 20;
    /** 选了样本档但没填行数时的默认值。 */
    public static final int DEFAULT_SAMPLE_ROWS = 5;

    private AiSharingRules() {
    }

    /**
     * 规范化一条策略。
     *
     * @param production 目标连接是否为生产连接（{@code environment = prod}）
     */
    public static AiConnectionPolicy normalize(long connectionId, String sharing, Integer sampleRowLimit, boolean production) {
        AiSchemaSharing level = AiSchemaSharing.parse(sharing);
        if (production && level.allowsSample()) {
            throw new IllegalArgumentException("生产连接不允许把样本数据发送给模型，请改用「仅结构」。");
        }
        if (!level.allowsSample()) return new AiConnectionPolicy(connectionId, level, 0);
        int rows = sampleRowLimit == null || sampleRowLimit <= 0 ? DEFAULT_SAMPLE_ROWS : sampleRowLimit;
        if (rows > MAX_SAMPLE_ROWS) {
            throw new IllegalArgumentException("样本行数上限为 " + MAX_SAMPLE_ROWS + " 行。");
        }
        return new AiConnectionPolicy(connectionId, level, rows);
    }

    /**
     * 读回一条已存策略时的兜底。
     *
     * <p>生产标记是可以后改的：一条连接先按测试库开了样本档，之后被改成生产环境，库里那行
     * 还留着旧档位。读的时候降级，而不是等到发请求时才发现。</p>
     */
    public static AiConnectionPolicy effective(AiConnectionPolicy stored, boolean production) {
        if (stored == null) return null;
        if (production && stored.sharing().allowsSample()) {
            return new AiConnectionPolicy(stored.connectionId(), AiSchemaSharing.STRUCTURE, 0);
        }
        return stored;
    }
}
