package com.example.dbadmin.service.migration.mapping;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLCreateViewStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableElement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.MigrationDtos.AiTranslateObjectResponse;
import com.example.dbadmin.dto.MigrationDtos.AssessmentFinding;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.CompilationDiagnostics;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.SchemaExportScript;
import com.example.dbadmin.service.ai.AiSettings;
import com.example.dbadmin.service.ai.AiSettingsService;
import com.example.dbadmin.service.ai.llm.LlmClient;
import com.example.dbadmin.service.ai.llm.LlmClientFactory;
import com.example.dbadmin.service.ai.llm.LlmRequest;
import com.example.dbadmin.service.ai.llm.LlmResponse;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalProgramObject;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可编程对象（视图 / 物化视图 / 触发器 / 存储过程 / 函数）转换器。
 *
 * <p>三档策略：
 * <ol>
 *   <li>同家族（Oracle↔OB-Oracle↔DM、MySQL↔OB-MySQL↔MariaDB）：规则清洗 Schema 限定名、DEFINER 与物理存储子句；</li>
 *   <li>跨家族简单视图：基于 Druid AST + 常用函数规则（NVL/SYSDATE/FROM DUAL/引号）生成候选 SQL；</li>
 *   <li>跨家族复杂过程/函数/触发器：保留源码待人工确认，并支持复用 MyDataDev 既有 AI 框架做按需转译 + 目标库只读试编译。</li>
 * </ol></p>
 */
@Component
public class ProgramObjectTranslator {
    private static final Pattern ORACLE_EDITIONABLE = Pattern.compile("(?i)\\b(NON)?EDITIONABLE\\s+");
    private static final Pattern ORACLE_FROM_DUAL = Pattern.compile("(?i)\\s+FROM\\s+(?:SYS\\.)?DUAL\\b");
    private static final Pattern ORACLE_NVL = Pattern.compile("(?i)\\bNVL\\s*\\(");
    private static final Pattern ORACLE_SYSDATE = Pattern.compile("(?i)\\bSYSDATE\\b");
    private static final Pattern ORACLE_SYSTIMESTAMP = Pattern.compile("(?i)\\bSYSTIMESTAMP\\b");
    private static final Pattern CODE_FENCE = Pattern.compile("(?s)```(?:sql)?\\s*(.*?)```");

    private final AiSettingsService aiSettings;
    private final LlmClientFactory llmClients;
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;

    public ProgramObjectTranslator(
            AiSettingsService aiSettings,
            LlmClientFactory llmClients,
            ConnectionService connections,
            DialectRegistry dialectRegistry
    ) {
        this.aiSettings = aiSettings;
        this.llmClients = llmClients;
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
    }

    public record TranslationResult(
            String targetName,
            String translationMode,
            String targetSql,
            List<AssessmentFinding> findings
    ) {
    }

    public TranslationResult translateDeterministic(
            DbTargetDialect source,
            DbTargetDialect target,
            String sourceSchema,
            String targetSchema,
            CanonicalProgramObject object,
            MigrationOptions options
    ) {
        return translateDeterministic(source, target, sourceSchema, targetSchema, object, options, Map.of());
    }

    public TranslationResult translateDeterministic(
            DbTargetDialect source,
            DbTargetDialect target,
            String sourceSchema,
            String targetSchema,
            CanonicalProgramObject object,
            MigrationOptions options,
            Map<String, String> sourceToTargetTables
    ) {
        List<AssessmentFinding> findings = new ArrayList<>();
        String targetName = IdentifierMapper.map(object.name(), target, options.identifierCasePolicy()).targetName();
        String key = object.kind() + ":" + object.name();
        String override = options.programObjectOverrides().get(key);
        if (override != null && !override.isBlank()) {
            return new TranslationResult(targetName, "USER_EDITED", override.trim(), List.of());
        }

        // 检查目标库是否支持该对象类型
        if (!targetSupportsObjectKind(target, object.kind())) {
            findings.add(new AssessmentFinding("WARNING", "PROGRAM_OBJECT_UNSUPPORTED", object.kind(), object.name(), null,
                    "目标库 " + target.code() + " 不支持对象类型 " + object.kind() + "，默认跳过",
                    "请考虑改写为目标库等价机制（如物化视图改定时汇总表、触发器改应用层逻辑）"));
            return new TranslationResult(targetName, "UNSUPPORTED", "", findings);
        }

        String cleaned = SchemaExportScript.stripDefiner(object.sourceSql());
        if (source != null && source.sameFamily(target)) {
            String rewritten = rewriteSameFamily(cleaned, source, target, sourceSchema, targetSchema);
            return new TranslationResult(targetName, "SAME_FAMILY_AUTO", rewritten, findings);
        }

        if ("VIEW".equalsIgnoreCase(object.kind())) {
            String candidate = translateViewCrossFamily(
                    cleaned, source, target, sourceSchema, targetSchema, targetName, options, sourceToTargetTables, findings);
            return new TranslationResult(targetName, "RULE_ASSISTED", candidate, findings);
        }

        findings.add(new AssessmentFinding("WARNING", "PROGRAM_OBJECT_MANUAL_REVIEW", object.kind(), object.name(), null,
                "跨数据库家族的 " + object.kind() + "（" + object.name() + "）无法仅靠静态规则 100% 等价转换，需人工复核或使用「AI 辅助转译」",
                "点击「AI 辅助转译」获取目标方言候选脚本，经目标库校验并确认后再勾选执行"));
        return new TranslationResult(targetName, "MANUAL_REQUIRED", "", findings);
    }

    public boolean isAiAvailable(Long sourceConnectionId, Long targetConnectionId, String actor) {
        try {
            aiSettings.requireEnabled(actor);
            if (sourceConnectionId != null) aiSettings.requireSharedConnection(sourceConnectionId);
            if (targetConnectionId != null) aiSettings.requireSharedConnection(targetConnectionId);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 复用 MyDataDev 既有 AI 模型通道转译单个可编程对象，并在目标连接上做只读编译校验（视图走 EXPLAIN/compileQuery）。 */
    public AiTranslateObjectResponse translateWithAi(
            DbTargetDialect source,
            DbTargetDialect target,
            Long sourceConnectionId,
            Long targetConnectionId,
            String targetSchema,
            CanonicalProgramObject object,
            String targetName,
            String actor
    ) throws Exception {
        AiSettings current = aiSettings.requireEnabled(actor);
        if (sourceConnectionId != null) aiSettings.requireSharedConnection(sourceConnectionId);
        if (targetConnectionId != null) aiSettings.requireSharedConnection(targetConnectionId);

        String systemPrompt = """
                你是资深数据库迁移架构师。你的任务是把源数据库的对象定义精确翻译为目标数据库方言的合法可执行 DDL。
                要求：
                1. 只输出一个 ```sql 代码块包含完整目标 DDL，不要夹带多余解释；
                2. 严格保持业务语义等价，保留原过滤条件、连接语义、空值处理和排序行为；
                3. 目标对象名使用给定的目标名称和目标 Schema；
                4. 如果存在无法 100% 等价转换的方言特性，在 SQL 顶部以 -- TODO: 注释标明。
                """;
        String userPrompt = "源数据库类型：" + source.code() + "\n"
                + "目标数据库类型：" + target.code() + "\n"
                + "对象类型：" + object.kind() + "\n"
                + "目标对象全名：" + target.qualifiedName(targetSchema, targetName) + "\n"
                + "源对象 DDL：\n```sql\n" + object.sourceSql() + "\n```";

        LlmClient client = llmClients.create(current);
        LlmResponse response = client.complete(new LlmRequest(
                systemPrompt, userPrompt, 4096L
        ));
        String translated = extractSql(response.text());
        List<String> notes = new ArrayList<>();
        boolean compiled = false;
        String compileMessage = "未执行目标库试编译（仅生成候选脚本，请人工审阅）";

        if (targetConnectionId != null && !translated.isBlank()) {
            if ("VIEW".equalsIgnoreCase(object.kind())) {
                String selectBody = extractViewSelect(translated);
                if (selectBody != null) {
                    DbConnection targetConn = connections.require(targetConnectionId);
                    DatabaseDialect targetDialect = dialectRegistry.dialectFor(targetConn);
                    try (Connection conn = connections.open(targetConnectionId, targetSchema)) {
                        targetDialect.compileQuery(conn, selectBody, 10);
                        compiled = true;
                        compileMessage = "视图查询主体已在目标库通过只读编译校验";
                    } catch (Exception error) {
                        compiled = false;
                        compileMessage = "目标库试编译未通过（若目标 Schema/表尚未创建，将在迁移执行时自动创建）：" + error.getMessage();
                        notes.add("如目标 Schema 或源表尚未建好，可先审阅 SQL 并在迁移执行后查看目标库编译结果");
                    }
                }
            } else {
                notes.add("存储过程/函数/触发器不在只读阶段直接落库，请在勾选执行后查看目标库编译结果");
            }
        }
        return new AiTranslateObjectResponse(
                object.kind() + ":" + object.name(),
                translated,
                compiled,
                compileMessage,
                List.copyOf(notes)
        );
    }

    private String rewriteSameFamily(String sql, DbTargetDialect source, DbTargetDialect target, String sourceSchema, String targetSchema) {
        String out = sql.trim();
        if (source.isOracleFamily()) {
            out = ORACLE_EDITIONABLE.matcher(out).replaceAll("");
        }
        if (sourceSchema != null && !sourceSchema.isBlank() && targetSchema != null && !targetSchema.isBlank()
                && !sourceSchema.equalsIgnoreCase(targetSchema)) {
            out = out.replaceAll("(?i)\"" + Pattern.quote(sourceSchema) + "\"\\.", target.quoteIdentifier(targetSchema) + ".");
            out = out.replaceAll("(?i)`" + Pattern.quote(sourceSchema) + "`\\.", target.quoteIdentifier(targetSchema) + ".");
            out = out.replaceAll("(?i)\\b" + Pattern.quote(sourceSchema) + "\\.", target.quoteIdentifier(targetSchema) + ".");
        }
        return out;
    }

    private String translateViewCrossFamily(
            String sql,
            DbTargetDialect source,
            DbTargetDialect target,
            String sourceSchema,
            String targetSchema,
            String targetName,
            MigrationOptions options,
            Map<String, String> sourceToTargetTables,
            List<AssessmentFinding> findings
    ) {
        String rewritten = ORACLE_EDITIONABLE.matcher(sql.trim()).replaceAll("");
        if (source != null && source.isOracleFamily() && !target.isOracleFamily()) {
            rewritten = ORACLE_NVL.matcher(rewritten).replaceAll("COALESCE(");
            rewritten = ORACLE_SYSDATE.matcher(rewritten).replaceAll(
                    target == DbTargetDialect.SQLSERVER ? "GETDATE()" : "CURRENT_TIMESTAMP");
            rewritten = ORACLE_SYSTIMESTAMP.matcher(rewritten).replaceAll(
                    target == DbTargetDialect.SQLSERVER ? "SYSUTCDATETIME()" : "CURRENT_TIMESTAMP");
            if (target == DbTargetDialect.POSTGRESQL || target == DbTargetDialect.SQLSERVER || target == DbTargetDialect.SQLITE) {
                rewritten = ORACLE_FROM_DUAL.matcher(rewritten).replaceAll("");
            }
        }
        // 尝试用 Druid AST 解析、重写视图名与引用表名（按大小写策略与表名映射）并按目标方言重排
        boolean astRewritten = false;
        try {
            DbType srcDruid = toDruidDbType(source);
            DbType tgtDruid = toDruidDbType(target);
            if (srcDruid != null && tgtDruid != null) {
                SQLStatement stmt = SQLUtils.parseSingleStatement(rewritten, srcDruid);
                rewriteViewAstIdentifiers(stmt, target, sourceSchema, targetSchema, targetName, options, sourceToTargetTables);
                rewritten = SQLUtils.toSQLString(stmt, tgtDruid);
                astRewritten = true;
            }
        } catch (Exception ignored) {
            findings.add(new AssessmentFinding("WARNING", "VIEW_AST_FALLBACK", "VIEW", targetName, null,
                    "视图语法含方言特有写法，已退回正则规则改写，建议人工检查或点击「AI 辅助转译」", null));
        }
        if (!astRewritten && sourceToTargetTables != null && !sourceToTargetTables.isEmpty()) {
            for (var e : sourceToTargetTables.entrySet()) {
                rewritten = rewritten.replaceAll("(?i)\\b" + Pattern.quote(e.getKey()) + "\\b", Matcher.quoteReplacement(e.getValue()));
            }
        }
        if (sourceSchema != null && !sourceSchema.isBlank() && targetSchema != null && !targetSchema.isBlank()
                && !sourceSchema.equalsIgnoreCase(targetSchema)) {
            rewritten = rewritten.replaceAll("(?i)[\"`\\[]" + Pattern.quote(sourceSchema) + "[\"`\\]]\\.",
                    target.quoteIdentifier(targetSchema) + ".");
        }
        if (target.isMySqlFamily() && (source == null || !source.isMySqlFamily())) {
            rewritten = convertDoubleQuotedIdentifiersToBackticks(rewritten);
        } else if (!target.isMySqlFamily() && source != null && source.isMySqlFamily()) {
            rewritten = convertBackticksToTargetQuotes(rewritten, target);
        }
        rewritten = stripTrailingSemicolon(rewritten);
        findings.add(new AssessmentFinding("WARNING", "VIEW_NEEDS_REVIEW", "VIEW", targetName, null,
                "跨库视图已由规则生成候选语句，建议在目标库试运行核对函数与列名大小写", null));
        return rewritten;
    }

    private static void rewriteViewAstIdentifiers(
            SQLStatement stmt,
            DbTargetDialect target,
            String sourceSchema,
            String targetSchema,
            String targetViewName,
            MigrationOptions options,
            Map<String, String> sourceToTargetTables
    ) {
        String casePolicy = options == null ? "PRESERVE" : options.identifierCasePolicy();
        if (stmt instanceof SQLCreateViewStatement viewStmt) {
            if (targetSchema != null && !targetSchema.isBlank() && target != DbTargetDialect.SQLITE) {
                viewStmt.setName(new SQLPropertyExpr(new SQLIdentifierExpr(targetSchema), targetViewName));
            } else {
                viewStmt.setName(new SQLIdentifierExpr(targetViewName));
            }
            if (viewStmt.getColumns() != null) {
                for (SQLTableElement el : viewStmt.getColumns()) {
                    if (el instanceof SQLCreateViewStatement.Column col && col.getExpr() instanceof SQLIdentifierExpr id) {
                        String mappedCol = IdentifierMapper.map(stripQuotes(id.getName()), target, casePolicy).targetName();
                        id.setName(mappedCol);
                    }
                }
            }
        }
        stmt.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLExprTableSource x) {
                String rawTable = stripQuotes(x.getTableName());
                if (rawTable != null && !rawTable.isBlank()) {
                    String mapped = sourceToTargetTables == null ? null : sourceToTargetTables.get(rawTable.toLowerCase(Locale.ROOT));
                    String finalTable = mapped != null ? mapped : IdentifierMapper.map(rawTable, target, casePolicy).targetName();
                    String rawOwner = stripQuotes(x.getSchema());
                    if (rawOwner != null && !rawOwner.isBlank()) {
                        String finalOwner = (sourceSchema != null && rawOwner.equalsIgnoreCase(sourceSchema)
                                && targetSchema != null && !targetSchema.isBlank())
                                ? targetSchema : rawOwner;
                        if (target == DbTargetDialect.SQLITE) {
                            x.setExpr(new SQLIdentifierExpr(finalTable));
                        } else {
                            x.setExpr(new SQLPropertyExpr(new SQLIdentifierExpr(finalOwner), finalTable));
                        }
                    } else if (mapped != null && targetSchema != null && !targetSchema.isBlank() && target != DbTargetDialect.SQLITE) {
                        x.setExpr(new SQLPropertyExpr(new SQLIdentifierExpr(targetSchema), finalTable));
                    } else {
                        x.setSimpleName(finalTable);
                    }
                }
                return true;
            }
        });
    }

    private static String stripQuotes(String ident) {
        if (ident == null) return null;
        String s = ident.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("`") && s.endsWith("`"))
                || (s.startsWith("[") && s.endsWith("]")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 将双引号标识符转为 MySQL 反引号（跳过单引号字符串字面量，避免误伤字符串内容）。 */
    private static String convertDoubleQuotedIdentifiersToBackticks(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
                out.append(c);
            } else if (!inSingle && c == '"') {
                out.append('`');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String convertBackticksToTargetQuotes(String sql, DbTargetDialect target) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
                out.append(c);
            } else if (!inSingle && c == '`') {
                int end = sql.indexOf('`', i + 1);
                if (end > i) {
                    out.append(target.quoteIdentifier(sql.substring(i + 1, end)));
                    i = end;
                } else {
                    out.append(c);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
    }

    private static boolean targetSupportsObjectKind(DbTargetDialect target, String kind) {
        String k = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        return switch (k) {
            case "VIEW" -> true;
            case "MATERIALIZED_VIEW" -> target.isOracleFamily() || target == DbTargetDialect.POSTGRESQL || target == DbTargetDialect.CLICKHOUSE;
            case "TRIGGER" -> target != DbTargetDialect.CLICKHOUSE;
            case "PROCEDURE" -> target != DbTargetDialect.SQLITE && target != DbTargetDialect.CLICKHOUSE && target != DbTargetDialect.H2;
            case "FUNCTION" -> target != DbTargetDialect.SQLITE;
            default -> target.isOracleFamily();
        };
    }

    private static DbType toDruidDbType(DbTargetDialect dialect) {
        if (dialect == null) return null;
        return switch (dialect) {
            case ORACLE -> DbType.oracle;
            case OCEANBASE_ORACLE -> DbType.oceanbase_oracle;
            case DAMENG -> DbType.dm;
            case MYSQL, OCEANBASE_MYSQL -> DbType.mysql;
            case MARIADB -> DbType.mariadb;
            case POSTGRESQL -> DbType.postgresql;
            case SQLSERVER -> DbType.sqlserver;
            case SQLITE -> DbType.sqlite;
            case H2 -> DbType.h2;
            case CLICKHOUSE -> DbType.clickhouse;
        };
    }

    private static String extractSql(String raw) {
        if (raw == null) return "";
        Matcher m = CODE_FENCE.matcher(raw);
        if (m.find()) return m.group(1).trim();
        return raw.trim();
    }

    private static String extractViewSelect(String ddl) {
        Matcher m = Pattern.compile("(?is)\\bAS\\s+(SELECT\\b.*|WITH\\b.*)$").matcher(ddl.trim());
        if (!m.find()) return null;
        String body = m.group(1).trim();
        if (body.endsWith(";")) body = body.substring(0, body.length() - 1).trim();
        return body;
    }
}
