package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.CellSerializer;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.core.SchemaObjectOperation;
import com.example.dbadmin.dto.ApiDtos.RoutineArgumentInput;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeRequest;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeResponse;
import com.example.dbadmin.dto.ApiDtos.RoutineOutParameter;
import com.example.dbadmin.dto.ApiDtos.RoutineResultItem;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectParameter;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectSummary;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.example.dbadmin.service.SchemaObjectService.elapsed;
import static com.example.dbadmin.service.SchemaObjectService.error;
import static com.example.dbadmin.service.SchemaObjectService.value;

/**
 * 存储过程与函数的调用：参数绑定、前台调用（可取消、受 {@code app.sql.timeout-seconds} 约束）与后台调用
 * （借命令窗口任务，见 CLAUDE.md「例程调用的超时与取消」），以及返回值、OUT 参数、结果集和例程输出的读取。
 *
 * <p>从 {@link SchemaObjectService} 拆出来：那边管对象目录、详情与 DDL 生命周期，这边只管「把它跑起来」。
 * 对象的解析、详情与结构版本校验仍走那边，调用前看到的对象和详情页上看到的是同一个。</p>
 */
@Service
public class RoutineInvocationService {
    /** 例程消息条数上限：一个循环里的 PUT_LINE 能刷出几十万行。 */
    private static final int MAX_ROUTINE_MESSAGES = 500;
    /** 每个结果集最多返回的行数；单元格数与字符数上限沿用 SqlResultReader。 */
    private static final int MAX_RESULT_ROWS = 500;
    /** 返回值与 OUT 参数的上限（结果集的上限在 SqlResultReader 里）。CLOB 先读回多少字符用于判断截断，与 SQL 工作台同值。 */
    private static final int MAX_CELL_TEXT = 100_000;
    private static final int MAX_CLOB_WINDOW_CHARS = 10_000;

    /** 前台调用的取消登记；为空时（部分单测）调用不可取消。 */
    private SqlExecutionRegistry executions;

    /** 后台调用借用命令窗口任务；为空时（部分单测）没有后台调用。 */
    private SqlFileExecutionService sqlFiles;

    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final ExecutionGuard executionGuard;
    private final AuditRepository audit;
    private final SqlHistoryRepository history;
    private final MetadataCacheService cache;
    private final AppProperties properties;
    private final SchemaObjectService objects;

    public RoutineInvocationService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            ExecutionGuard executionGuard,
            AuditRepository audit,
            SqlHistoryRepository history,
            MetadataCacheService cache,
            AppProperties properties,
            SchemaObjectService objects
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.executionGuard = executionGuard;
        this.audit = audit;
        this.history = history;
        this.cache = cache;
        this.properties = properties;
        this.objects = objects;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExecutionRegistry(SqlExecutionRegistry executions) {
        this.executions = executions;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSqlFileExecutions(SqlFileExecutionService sqlFiles) {
        this.sqlFiles = sqlFiles;
    }

    public RoutineInvokeResponse invoke(
            long connectionId,
            RoutineInvokeRequest request,
            String actor,
            String productionConfirmation
    ) throws Exception {
        DbConnection configured = connections.require(connectionId);
        executionGuard.requireMutationAllowed(configured, productionConfirmation);
        requireInvocable(configured, request);
        long started = System.nanoTime();
        int timeoutSeconds = Math.max(properties.getSql().getTimeoutSeconds(), 1);
        String historySql = null;
        // 登记到 SqlExecutionRegistry 之后，界面上的「取消」走的就是 SQL 工作台那个接口。
        Statement[] registered = {null};
        String[] executionId = {null};
        try (Connection connection = connections.open(connectionId)) {
            InvocationPlan plan = planInvocation(connection, configured, request);
            historySql = plan.historySql();
            // 旧警告会被当成这次调用的输出报出来，池化连接上尤其容易串味。
            connection.clearWarnings();
            enableRoutineOutput(connection, plan.dialect());
            RoutineInvokeResponse response = runInvocation(connection, plan, statement -> {
                if (executions != null) executionId[0] = executions.register(request.executionId(), connectionId, statement);
                registered[0] = statement;
            }, timeoutSeconds, started);
            audit.onConnection(actor, "OBJECT_INVOKE", connectionId, plan.auditTarget(), plan.auditDetail());
            history.insert(connectionId, historySql, "ROUTINE_INVOKE", "SUCCESS", response.elapsedMs(), null, actor);
            return response;
        } catch (Exception exception) {
            boolean cancelled = executions != null && registered[0] != null && executions.cancelRequested(executionId[0], registered[0]);
            history.insert(connectionId, historySql == null ? "CALL <schema-object>" : historySql, "ROUTINE_INVOKE", "FAILED", elapsed(started),
                    cancelled ? "已取消" : error(exception), actor);
            audit.onConnection(actor, "OBJECT_INVOKE_FAILED", connectionId, cancelled ? "已取消" : error(exception));
            throw invokeFailure(exception, cancelled, timeoutSeconds);
        } finally {
            if (executions != null && registered[0] != null) executions.unregister(executionId[0], registered[0]);
            // Routines may execute DDL internally, so metadata cannot be kept.
            cache.evictConnection(connectionId);
        }
    }

    /**
     * 后台调用：参数校验与前台同一套，执行交给命令窗口那条后台任务 —— 排队、每连接并发闸门、进度、取消、
     * 滚动日志全部沿用，语句超时是 {@code app.sql-file.statement-timeout-seconds} 而不是前台的
     * {@code app.sql.timeout-seconds}。跑几十分钟的批处理过程只能走这里：前台调用占着一个 HTTP 请求，
     * 浏览器、反向代理任何一环先断开，用户就再也不知道它跑完没有。
     *
     * <p>参数值只留在内存里交给执行线程，不落盘、不进审计与历史；落盘的脚本只是那句带占位符的调用。
     * 返回值、OUT 参数与结果集写进命令日志，与命令窗口打出查询结果是同一回事。</p>
     */
    public SqlFileExecutionResponse invokeInBackground(
            long connectionId,
            RoutineInvokeRequest request,
            String actor,
            String productionConfirmation
    ) throws Exception {
        if (sqlFiles == null) throw new IllegalStateException("后台执行不可用。");
        DbConnection configured = connections.require(connectionId);
        executionGuard.requireMutationAllowed(configured, productionConfirmation);
        requireInvocable(configured, request);
        InvocationPlan plan;
        try (Connection connection = connections.open(connectionId)) {
            plan = planInvocation(connection, configured, request);
        }
        return sqlFiles.submitRoutineCommand(connectionId, plan.historySql(), actor, productionConfirmation,
                (connection, context) -> runInBackground(connectionId, plan, actor, connection, context));
    }

    private SqlFileExecutionService.CommandUnitResult runInBackground(
            long connectionId,
            InvocationPlan plan,
            String actor,
            Connection connection,
            SqlFileExecutionService.CommandUnitContext context
    ) throws Exception {
        long started = System.nanoTime();
        try {
            connection.clearWarnings();
            enableRoutineOutput(connection, plan.dialect());
            RoutineInvokeResponse response = runInvocation(connection, plan, context::register, context.timeoutSeconds(), started);
            audit.onConnection(actor, "OBJECT_INVOKE", connectionId, plan.auditTarget(), "后台执行; " + plan.auditDetail());
            history.insert(connectionId, plan.historySql(), "ROUTINE_INVOKE", "SUCCESS", response.elapsedMs(), null, actor);
            return RoutineCommandText.describe(response);
        } catch (Exception error) {
            boolean cancelled = context.cancelRequested();
            history.insert(connectionId, plan.historySql(), "ROUTINE_INVOKE", "FAILED", elapsed(started), cancelled ? "已取消" : error(error), actor);
            audit.onConnection(actor, "OBJECT_INVOKE_FAILED", connectionId, cancelled ? "后台执行已取消" : "后台执行; " + error(error));
            if (!cancelled && SqlTimeouts.isTimeout(error)) {
                // 不挂 cause 会丢掉驱动原文；挂上之后命令日志显示的是这条翻译过的说明。
                throw new java.sql.SQLTimeoutException("调用超过后台语句超时（" + RoutineCommandText.duration(context.timeoutSeconds())
                        + "）被中止；过程内部已经提交的部分不会回滚。数据库原文：" + error(error), null, 0, error);
            }
            throw error;
        } finally {
            cache.evictConnection(connectionId);
        }
    }

    private void requireInvocable(DbConnection configured, RoutineInvokeRequest request) {
        var reference = objects.decodeKey(request.objectKey());
        if (reference.kind() != SchemaObjectKind.PROCEDURE && reference.kind() != SchemaObjectKind.FUNCTION) {
            throw new IllegalArgumentException("仅存储过程和函数支持调用。");
        }
        objects.requireCapability(configured, reference.kind(), SchemaObjectOperation.INVOKE);
    }

    /** 一次调用要用到的全部东西：解析好的对象与参数、方言、写进历史的那句（只有占位符，没有参数值）。 */
    private record InvocationPlan(
            DatabaseDialect dialect,
            boolean asQuery,
            SchemaObjectKind kind,
            SchemaObjectSummary object,
            List<SchemaObjectParameter> parameters,
            Map<Integer, RoutineArgumentInput> inputs,
            String historySql,
            String auditTarget,
            String auditDetail
    ) { }

    /** 语句建好、执行之前调一次：登记取消。 */
    @FunctionalInterface
    interface StatementHook {
        void opened(Statement statement) throws java.sql.SQLException;
    }

    private InvocationPlan planInvocation(Connection connection, DbConnection configured, RoutineInvokeRequest request) throws Exception {
        var reference = objects.decodeKey(request.objectKey());
        var resolved = objects.resolve(connection, configured, reference);
        SchemaObjectDetail live = objects.loadDetail(connection, configured, resolved);
        objects.requireFresh(request.structureVersion(), live.structureVersion());
        DatabaseDialect dialect = dialectRegistry.dialectFor(configured);
        List<SchemaObjectParameter> parameters = live.parameters();
        Map<Integer, RoutineArgumentInput> inputs = new HashMap<>();
        Set<Integer> inputPositions = parameters.stream()
                .filter(parameter -> parameter.mode().equals("IN") || parameter.mode().equals("INOUT"))
                .map(SchemaObjectParameter::position)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (RoutineArgumentInput input : request.arguments() == null ? List.<RoutineArgumentInput>of() : request.arguments()) {
            if (!inputPositions.contains(input.position())) throw new IllegalArgumentException("例程参数位置无效：" + input.position());
            if (inputs.put(input.position(), input) != null) throw new IllegalArgumentException("例程参数位置不能重复：" + input.position());
        }
        for (SchemaObjectParameter parameter : parameters) {
            if ((parameter.mode().equals("IN") || parameter.mode().equals("INOUT")) && !inputs.containsKey(parameter.position())) {
                throw new IllegalArgumentException("请填写参数：" + parameterLabel(parameter));
            }
        }
        boolean asQuery = dialect.schemaObjects().invokesRoutinesAsQuery();
        String auditDetail = parameters.stream()
                .map(parameter -> parameterLabel(parameter) + ":" + value(parameter.typeName()) + ":" + parameter.mode())
                .reduce((left, right) -> left + ", " + right).orElse("无参数");
        return new InvocationPlan(dialect, asQuery, reference.kind(), resolved.object(), parameters, Map.copyOf(inputs),
                routineSql(dialect, asQuery, reference.kind(), resolved.object(), parameters),
                "object:" + objects.confirmationTarget(live.object()), auditDetail);
    }

    private RoutineInvokeResponse runInvocation(Connection connection, InvocationPlan plan, StatementHook hook,
                                                int timeoutSeconds, long started) throws Exception {
        return plan.asQuery()
                ? invokePrepared(connection, plan.dialect(), plan.kind(), plan.object(), plan.parameters(), plan.inputs(), hook, timeoutSeconds, started)
                : invokeCallable(connection, plan.dialect(), plan.kind(), plan.object(), plan.parameters(), plan.inputs(), hook, timeoutSeconds, started);
    }

    /**
     * 前台调用失败时给用户的那句话。取消与超时在驱动层常是同一个错误（PostgreSQL 57014、Oracle ORA-01013），
     * 先问登记处是不是用户点的取消，再判超时；两样都要说清「已提交的部分不会回滚」，并给出长任务该走的路。
     */
    private Exception invokeFailure(Exception error, boolean cancelled, int timeoutSeconds) {
        if (cancelled) {
            return new ApiProblemException(HttpStatus.CONFLICT, "SQL_CANCELLED",
                    "调用已取消，数据库已中止执行。过程内部已经提交的部分不会回滚，请先核对结果。");
        }
        if (SqlTimeouts.isTimeout(error)) {
            return new ApiProblemException(HttpStatus.BAD_REQUEST, "SQL_TIMEOUT",
                    "调用超过前台调用的上限 " + RoutineCommandText.duration(timeoutSeconds) + "，已被数据库中止；过程内部已经提交的部分不会回滚，请先核对结果。"
                            + "运行时间长的过程请选择「后台执行」（每条语句最长 "
                            + RoutineCommandText.duration(properties.getSqlFile().getStatementTimeoutSeconds()) + "，可查看进度、随时取消）。"
                            + "数据库原文：" + error(error));
        }
        return error;
    }

    /**
     * 例程执行期间产生的消息。
     *
     * <p>两个来源：JDBC 的 SQLWarning 链（PostgreSQL 的 {@code RAISE NOTICE}、MySQL 的警告都
     * 走这里），以及 Oracle 的 {@code DBMS_OUTPUT} 缓冲 —— 后者不显式读回就永远看不到，而
     * PL/SQL 里排查问题基本全靠它。</p>
     *
     * <p>整段吞异常：这是调用之外的诊断信息，读不回来不该把一次已经成功的调用改判成失败。</p>
     */
    private List<String> routineMessages(Connection connection, DatabaseDialect dialect, Statement statement) {
        return RoutineOutput.read(connection, dialect, statement, MAX_ROUTINE_MESSAGES);
    }

    /** 打开服务端的输出缓冲。失败无所谓：拿不到 DBMS_OUTPUT 只是少一份诊断信息。 */
    private void enableRoutineOutput(Connection connection, DatabaseDialect dialect) {
        RoutineOutput.enable(connection, dialect);
    }

    private RoutineInvokeResponse invokePrepared(
            Connection connection,
            DatabaseDialect dialect,
            SchemaObjectKind kind,
            SchemaObjectSummary object,
            List<SchemaObjectParameter> parameters,
            Map<Integer, RoutineArgumentInput> inputs,
            StatementHook hook,
            int timeoutSeconds,
            long started
    ) throws Exception {
        List<SchemaObjectParameter> inputParameters = parameters.stream()
                .filter(parameter -> parameter.mode().equals("IN") || parameter.mode().equals("INOUT"))
                .toList();
        String qualified = dialect.qualifiedName(object.schemaName(), object.name());
        String placeholders = String.join(", ", inputParameters.stream().map(ignored -> "?").toList());
        String sql = kind == SchemaObjectKind.PROCEDURE ? "CALL " + qualified + "(" + placeholders + ")"
                : "SELECT * FROM " + qualified + "(" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(timeoutSeconds, 1));
            hook.opened(statement);
            for (int index = 0; index < inputParameters.size(); index++) {
                bind(statement, index + 1, inputParameters.get(index), inputs.get(inputParameters.get(index).position()));
            }
            List<RoutineResultItem> results = readResults(statement, statement.execute(), started, dialect);
            Object returnValue = kind == SchemaObjectKind.FUNCTION && !results.isEmpty() && results.get(0).result() != null
                    && !results.get(0).result().rows().isEmpty() && !results.get(0).result().rows().get(0).isEmpty()
                    ? results.get(0).result().rows().get(0).get(0) : null;
            return new RoutineInvokeResponse("SUCCESS", elapsed(started), returnValue, List.of(), results,
                    results.stream().anyMatch(item -> item.result() != null && item.result().truncated()),
                    routineMessages(connection, dialect, statement));
        }
    }

    private RoutineInvokeResponse invokeCallable(
            Connection connection,
            DatabaseDialect dialect,
            SchemaObjectKind kind,
            SchemaObjectSummary object,
            List<SchemaObjectParameter> parameters,
            Map<Integer, RoutineArgumentInput> inputs,
            StatementHook hook,
            int timeoutSeconds,
            long started
    ) throws Exception {
        List<SchemaObjectParameter> callParameters = parameters.stream().filter(parameter -> !parameter.mode().equals("RETURN")).toList();
        boolean hasReturn = kind == SchemaObjectKind.FUNCTION;
        String placeholders = String.join(", ", callParameters.stream().map(ignored -> "?").toList());
        String sql = "{" + (hasReturn ? "? = " : "") + "call " + dialect.qualifiedName(object.schemaName(), object.name()) + "(" + placeholders + ")}";
        try (CallableStatement statement = connection.prepareCall(sql)) {
            statement.setQueryTimeout(Math.max(timeoutSeconds, 1));
            hook.opened(statement);
            int jdbcIndex = 1;
            if (hasReturn) {
                SchemaObjectParameter returned = parameters.stream().filter(parameter -> parameter.mode().equals("RETURN")).findFirst().orElse(null);
                statement.registerOutParameter(jdbcIndex++, safeJdbcType(returned));
            }
            Map<SchemaObjectParameter, Integer> positions = new LinkedHashMap<>();
            for (SchemaObjectParameter parameter : callParameters) {
                positions.put(parameter, jdbcIndex);
                if (parameter.mode().equals("OUT") || parameter.mode().equals("INOUT")) {
                    statement.registerOutParameter(jdbcIndex, safeJdbcType(parameter));
                }
                if (parameter.mode().equals("IN") || parameter.mode().equals("INOUT")) {
                    bind(statement, jdbcIndex, parameter, inputs.get(parameter.position()));
                }
                jdbcIndex++;
            }
            boolean hasResult = statement.execute();
            // Some drivers (notably H2) expose a function return value through the
            // current result set and make it unavailable after getMoreResults().
            Object returnValue = hasReturn ? serializable(statement.getObject(1), MAX_CELL_TEXT) : null;
            List<RoutineResultItem> results = readResults(statement, hasResult, started, dialect);
            List<RoutineOutParameter> out = new ArrayList<>();
            for (Map.Entry<SchemaObjectParameter, Integer> entry : positions.entrySet()) {
                SchemaObjectParameter parameter = entry.getKey();
                if (parameter.mode().equals("OUT") || parameter.mode().equals("INOUT")) {
                    out.add(new RoutineOutParameter(parameter.name(), parameter.typeName(), serializable(statement.getObject(entry.getValue()), MAX_CELL_TEXT)));
                }
            }
            return new RoutineInvokeResponse("SUCCESS", elapsed(started), returnValue, out, results,
                    results.stream().anyMatch(item -> item.result() != null && item.result().truncated()),
                    routineMessages(connection, dialect, statement));
        }
    }

    /**
     * 例程返回的结果集与更新计数。结果集走 {@link SqlResultReader}：与 SQL 工作台同一套上限、二进制描述与截断说法 ——
     * 例程结果就显示在同一张结果网格里。原来那份自写的读取在单元格文本被截断时不标 truncated、也不给截断原因，
     * 截掉一半的长文本在界面上看起来就是完整值；只能从流里读的二进制列也没有单独处理。
     */
    static List<RoutineResultItem> readResults(Statement statement, boolean hasResult, long started, DatabaseDialect dialect) throws Exception {
        List<RoutineResultItem> results = new ArrayList<>();
        int resultCount = 0;
        while (resultCount++ < 20) {
            if (hasResult) {
                try (ResultSet rs = statement.getResultSet()) {
                    results.add(new RoutineResultItem("RESULT_SET", SqlResultReader.readResult(rs, started, MAX_RESULT_ROWS, dialect), null));
                }
            } else {
                int updateCount = statement.getUpdateCount();
                if (updateCount == -1) break;
                results.add(new RoutineResultItem("UPDATE_COUNT", null, updateCount));
            }
            hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        }
        return results;
    }

    private void bind(PreparedStatement statement, int index, SchemaObjectParameter parameter, RoutineArgumentInput input) throws Exception {
        if (input == null) throw new IllegalArgumentException("缺少参数：" + parameterLabel(parameter));
        int jdbcType = safeJdbcType(parameter);
        if (input.nullValue()) {
            statement.setNull(index, jdbcType);
            return;
        }
        String value = input.value() == null ? "" : input.value();
        Object converted = switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> new BigDecimal(value.trim());
            case Types.BOOLEAN, Types.BIT -> parseBoolean(value);
            case Types.DATE -> java.sql.Date.valueOf(LocalDate.parse(value.trim()));
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> java.sql.Time.valueOf(LocalTime.parse(value.trim()));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> java.sql.Timestamp.valueOf(LocalDateTime.parse(value.trim().replace(' ', 'T')));
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY,
                    Types.ARRAY, Types.STRUCT, Types.SQLXML, Types.REF, Types.REF_CURSOR, Types.JAVA_OBJECT ->
                    throw new IllegalArgumentException("暂不支持输入复杂参数类型：" + value(parameter.typeName()));
            default -> value;
        };
        statement.setObject(index, converted, jdbcType);
    }

    private boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "是" -> true;
            case "false", "0", "no", "n", "否" -> false;
            default -> throw new IllegalArgumentException("布尔参数必须是 true/false 或 1/0。");
        };
    }

    /**
     * 例程执行结果的单元格取值。
     *
     * <p>走的是与 SQL 工作台、表数据浏览、执行计划、导出完全同一套规则（{@link CellSerializer}）——
     * 这里产出的也是一个 {@code SqlResult}，渲染在同一张结果网格里。自己再抄一份的下场是漂移：
     * 这条路此前用 {@code value.toString()} 收尾，整点时间戳因此比别处多一个 {@code .0}；
     * {@code BigInteger} 也漏在了「转成字符串」那一档外面，被当成 JSON 数字发出去，
     * 而浏览器按双精度解析 —— MySQL 的 {@code BIGINT UNSIGNED} 正是 {@code BigInteger}，
     * 19 位的 ID 会被静默改写。</p>
     */
    private Object serializable(Object value, int maxText) throws Exception {
        return CellSerializer.serialize(value, maxText, MAX_CLOB_WINDOW_CHARS);
    }

    private String routineSql(DatabaseDialect dialect, boolean asQuery, SchemaObjectKind kind, SchemaObjectSummary object, List<SchemaObjectParameter> parameters) {
        int inputCount = (int) parameters.stream().filter(parameter -> parameter.mode().equals("IN") || parameter.mode().equals("INOUT")).count();
        String placeholders = String.join(", ", java.util.Collections.nCopies(inputCount, "?"));
        String qualified = dialect.qualifiedName(object.schemaName(), object.name());
        if (asQuery) {
            return kind == SchemaObjectKind.PROCEDURE ? "CALL " + qualified + "(" + placeholders + ")" : "SELECT * FROM " + qualified + "(" + placeholders + ")";
        }
        return "{" + (kind == SchemaObjectKind.FUNCTION ? "? = " : "") + "call " + qualified + "(" + placeholders + ")}";
    }

    private int safeJdbcType(SchemaObjectParameter parameter) {
        return parameter == null || parameter.jdbcType() == null || parameter.jdbcType() == Types.NULL ? Types.VARCHAR : parameter.jdbcType();
    }

    private String parameterLabel(SchemaObjectParameter parameter) {
        return parameter.name() == null || parameter.name().isBlank() ? "参数 " + parameter.position() : parameter.name();
    }
}
