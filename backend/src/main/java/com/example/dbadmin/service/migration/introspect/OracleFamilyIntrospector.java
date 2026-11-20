package com.example.dbadmin.service.migration.introspect;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalCheckConstraint;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalForeignKey;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalIndex;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalProgramObject;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalSequence;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IdentitySpec;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IndexColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IndexKind;
import com.example.dbadmin.service.migration.model.CanonicalSchema.LengthUnit;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;

import java.io.Reader;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Oracle 家族（Oracle / OceanBase Oracle 模式 / 达梦 DM8）高性能深度元数据抽取器。
 *
 * <p>针对万表级大型企业 Oracle 库（如用友 NCC / BIP / 核心财务库）做了专项优化：
 * <ul>
 *   <li>当目标 Schema 与登录用户一致时自动切 {@code USER_*} 视图，避开 {@code ALL_*} 的逐行权限联查；</li>
 *   <li>全部元数据游标设置 {@code fetchSize=2000} 且主列查询不直接 SELECT {@code LONG} 类型（{@code DATA_DEFAULT} /
 *       {@code SEARCH_CONDITION} / {@code COLUMN_EXPRESSION}），彻底消除 ojdbc 退化为逐行 1 RTT 导致超时的顽疾；</li>
 *   <li>仅对 {@code DEFAULT_LENGTH > 0} 的列通过单次只读 PL/SQL 临时 CLOB 批量回传默认值（1 次网络往返）；</li>
 *   <li>CHECK 约束优先查 Oracle 12c+ {@code SEARCH_CONDITION_VC} 且过滤 {@code GENERATED = 'USER NAME'}，跳过全部系统自动 NOT NULL 约束。</li>
 * </ul></p>
 */
public class OracleFamilyIntrospector extends JdbcBaseIntrospector {
    private static final Pattern TRIVIAL_NOT_NULL = Pattern.compile("^\"?[A-Za-z0-9_$#]+\"?\\s+IS\\s+NOT\\s+NULL$", Pattern.CASE_INSENSITIVE);
    private static final int FALLBACK_LONG_DEFAULT_CAP = 3000;

    @Override
    public boolean supports(DbTargetDialect dialect) {
        return dialect != null && dialect.isOracleFamily();
    }

    @Override
    public CanonicalSchema introspect(
            Connection connection,
            DbConnection dbConnection,
            DatabaseDialect dialect,
            DbTargetDialect sourceDialect,
            String schemaName,
            MigrationOptions options,
            List<String> warnings
    ) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        String version = safeVersion(meta);
        String resolvedSchema = resolveSchema(connection, dialect, sourceDialect, schemaName);
        String owner = resolvedSchema.toUpperCase(Locale.ROOT);
        boolean ownSchema = isOwnSchema(meta, owner);

        Map<String, TableMeta> tableMetas = loadTableMetas(connection, ownSchema, owner);
        List<String> tableNames = tableMetas.isEmpty()
                ? listTablesJdbc(connection, meta, dialect, resolvedSchema)
                : new ArrayList<>(tableMetas.keySet());
        tableNames = filterTables(tableNames, options.selectedTables());
        Set<String> tableFilter = new LinkedHashSet<>(tableNames);

        Map<String, String> tableComments = options.commentsEnabled()
                ? loadTableComments(connection, ownSchema, owner, tableFilter)
                : Map.of();
        Map<String, Map<String, String>> colComments = options.commentsEnabled()
                ? loadColumnComments(connection, ownSchema, owner, tableFilter)
                : Map.of();
        Map<String, List<CanonicalColumn>> columnsByTable = loadColumns(
                connection, ownSchema, owner, sourceDialect, tableFilter, colComments, warnings);
        Map<String, ConstraintBundle> constraintsByTable = loadConstraints(
                connection, ownSchema, owner, dialect, sourceDialect, tableFilter, options);
        Map<String, List<CanonicalIndex>> indexesByTable = options.indexesEnabled()
                ? loadIndexes(connection, ownSchema, owner, dialect, sourceDialect, tableFilter, constraintsByTable)
                : Map.of();

        List<CanonicalTable> tables = new ArrayList<>(tableNames.size());
        int seq = 1;
        for (String tableName : tableNames) {
            String tableId = makeTableId(seq++, tableName);
            List<CanonicalColumn> cols = columnsByTable.get(tableName);
            if (cols == null || cols.isEmpty()) {
                tables.add(readTableJdbc(connection, meta, dialect, sourceDialect, resolvedSchema, tableName, tableId));
                continue;
            }
            TableMeta tm = tableMetas.get(tableName);
            ConstraintBundle cb = constraintsByTable.getOrDefault(tableName, ConstraintBundle.EMPTY);
            List<CanonicalIndex> idxs = indexesByTable.getOrDefault(tableName, List.of());
            tables.add(new CanonicalTable(
                    tableId,
                    tableName,
                    tableComments.get(tableName),
                    List.copyOf(cols),
                    cb.pkName,
                    List.copyOf(cb.pkColumns),
                    List.copyOf(idxs),
                    List.copyOf(cb.foreignKeys),
                    List.copyOf(cb.checks),
                    tm != null && tm.partitioned ? "PARTITIONED" : null,
                    tm == null ? null : tm.estimatedRows,
                    null
            ));
        }

        List<CanonicalSequence> sequences = options.sequencesEnabled()
                ? loadSequences(connection, ownSchema, owner, warnings)
                : List.of();
        List<CanonicalProgramObject> programObjects = readProgramObjects(connection, dialect, owner, options, warnings);
        String snapshot = captureSnapshotToken(connection, sourceDialect, options, warnings);

        return new CanonicalSchema(
                CanonicalSchema.CURRENT_FORMAT_VERSION,
                sourceDialect.code(),
                sourceDialect.family().name(),
                version,
                detectCharset(connection, sourceDialect),
                resolvedSchema,
                snapshot,
                List.copyOf(tables),
                List.copyOf(sequences),
                List.copyOf(programObjects)
        );
    }

    private static boolean isOwnSchema(DatabaseMetaData meta, String owner) {
        try {
            String user = meta.getUserName();
            return user != null && !user.isBlank() && user.trim().equalsIgnoreCase(owner);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static PreparedStatement prepareScoped(Connection connection, String sql, boolean ownSchema, String owner) throws SQLException {
        PreparedStatement ps = prepareMetaQuery(connection, sql);
        if (!ownSchema) {
            ps.setString(1, owner);
        }
        return ps;
    }

    @Override
    protected String detectCharset(Connection connection, DbTargetDialect sourceDialect) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT VALUE FROM NLS_DATABASE_PARAMETERS WHERE PARAMETER = 'NLS_CHARACTERSET'")) {
            if (rs.next()) {
                String cs = rs.getString(1);
                if (cs != null && !cs.isBlank()) return cs.trim();
            }
        } catch (Exception ignored) {
        }
        return "AL32UTF8";
    }

    @Override
    protected String captureSnapshotToken(Connection connection, DbTargetDialect sourceDialect, MigrationOptions options, List<String> warnings) {
        if (sourceDialect != DbTargetDialect.ORACLE || !options.oracleScnSnapshotEnabled()) {
            return null;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT CURRENT_SCN FROM V$DATABASE")) {
            if (rs.next()) {
                String scn = rs.getString(1);
                if (scn != null && !scn.isBlank()) return "SCN:" + scn.trim();
            }
        } catch (Exception ignored) {
            warnings.add("当前账号无权读取 V$DATABASE.CURRENT_SCN，本次迁移将不使用 Flashback SCN 一致性快照；如源库有并发写入建议在只读备库或停写窗口执行。");
        }
        return null;
    }

    private Map<String, TableMeta> loadTableMetas(Connection connection, boolean ownSchema, String owner) {
        Map<String, TableMeta> result = new LinkedHashMap<>();
        String sql = ownSchema
                ? "SELECT TABLE_NAME, NUM_ROWS, PARTITIONED FROM USER_TABLES WHERE TEMPORARY = 'N' ORDER BY TABLE_NAME"
                : "SELECT TABLE_NAME, NUM_ROWS, PARTITIONED FROM ALL_TABLES WHERE OWNER = ? AND TEMPORARY = 'N' ORDER BY TABLE_NAME";
        try (PreparedStatement ps = prepareScoped(connection, sql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name == null || name.startsWith("BIN$")) continue;
                result.put(name, new TableMeta(getLongOrNull(rs, "NUM_ROWS"), "YES".equalsIgnoreCase(rs.getString(3))));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, String> loadTableComments(Connection connection, boolean ownSchema, String owner, Set<String> tableFilter) {
        if (tableFilter.isEmpty()) return Map.of();
        Map<String, String> map = new HashMap<>();
        String sql = ownSchema
                ? "SELECT TABLE_NAME, COMMENTS FROM USER_TAB_COMMENTS WHERE COMMENTS IS NOT NULL"
                : "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND COMMENTS IS NOT NULL";
        try (PreparedStatement ps = prepareScoped(connection, sql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (!tableFilter.contains(table)) continue;
                String c = blankToNull(rs.getString(2));
                if (c != null) map.put(table, c);
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private Map<String, Map<String, String>> loadColumnComments(Connection connection, boolean ownSchema, String owner, Set<String> tableFilter) {
        if (tableFilter.isEmpty()) return Map.of();
        Map<String, Map<String, String>> map = new HashMap<>();
        String sql = ownSchema
                ? "SELECT TABLE_NAME, COLUMN_NAME, COMMENTS FROM USER_COL_COMMENTS WHERE COMMENTS IS NOT NULL"
                : "SELECT TABLE_NAME, COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = ? AND COMMENTS IS NOT NULL";
        try (PreparedStatement ps = prepareScoped(connection, sql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (!tableFilter.contains(table)) continue;
                String c = blankToNull(rs.getString(3));
                if (c != null) {
                    map.computeIfAbsent(table, k -> new HashMap<>()).put(rs.getString(2), c);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private Map<String, List<CanonicalColumn>> loadColumns(
            Connection connection,
            boolean ownSchema,
            String owner,
            DbTargetDialect sourceDialect,
            Set<String> tableFilter,
            Map<String, Map<String, String>> colComments,
            List<String> warnings
    ) {
        if (tableFilter.isEmpty()) return Map.of();

        // 第 1 遍：严禁包含 LONG 类型的 DATA_DEFAULT！配合 fetchSize=2000 几秒即可拉完 20 万列元数据。
        String richSql = ownSchema
                ? """
                  SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, CHAR_LENGTH, CHAR_USED,
                         DATA_PRECISION, DATA_SCALE, NULLABLE, DEFAULT_LENGTH, IDENTITY_COLUMN, VIRTUAL_COLUMN
                  FROM USER_TAB_COLS
                  WHERE HIDDEN_COLUMN = 'NO'
                  ORDER BY TABLE_NAME, COLUMN_ID
                  """
                : """
                  SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, CHAR_LENGTH, CHAR_USED,
                         DATA_PRECISION, DATA_SCALE, NULLABLE, DEFAULT_LENGTH, IDENTITY_COLUMN, VIRTUAL_COLUMN
                  FROM ALL_TAB_COLS
                  WHERE OWNER = ? AND HIDDEN_COLUMN = 'NO'
                  ORDER BY TABLE_NAME, COLUMN_ID
                  """;
        String basicSql = ownSchema
                ? """
                  SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, CHAR_LENGTH, CHAR_USED,
                         DATA_PRECISION, DATA_SCALE, NULLABLE, DEFAULT_LENGTH
                  FROM USER_TAB_COLUMNS
                  ORDER BY TABLE_NAME, COLUMN_ID
                  """
                : """
                  SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, CHAR_LENGTH, CHAR_USED,
                         DATA_PRECISION, DATA_SCALE, NULLABLE, DEFAULT_LENGTH
                  FROM ALL_TAB_COLUMNS
                  WHERE OWNER = ?
                  ORDER BY TABLE_NAME, COLUMN_ID
                  """;

        Map<String, List<RawColumn>> rawByTable = new LinkedHashMap<>();
        int[] defaultColCount = new int[1];
        if (!readRawColumns(connection, ownSchema, owner, tableFilter, richSql, true, rawByTable, defaultColCount)) {
            rawByTable.clear();
            defaultColCount[0] = 0;
            readRawColumns(connection, ownSchema, owner, tableFilter, basicSql, false, rawByTable, defaultColCount);
        }

        // 第 2 遍：仅当存在带默认值的列时，通过服务端单次 PL/SQL 临时 CLOB 批量提取 LONG 类型的 DATA_DEFAULT（零逐行 RTT）
        Map<String, Map<String, String>> defaultsByTable = defaultColCount[0] > 0
                ? loadColumnDefaults(connection, ownSchema, owner, sourceDialect, tableFilter, warnings)
                : Map.of();

        Map<String, List<CanonicalColumn>> result = new LinkedHashMap<>(rawByTable.size());
        for (var entry : rawByTable.entrySet()) {
            String tableName = entry.getKey();
            Map<String, String> tblComments = colComments.getOrDefault(tableName, Map.of());
            Map<String, String> tblDefaults = defaultsByTable.getOrDefault(tableName, Map.of());
            List<CanonicalColumn> cols = new ArrayList<>(entry.getValue().size());
            for (RawColumn rc : entry.getValue()) {
                String dataDefault = rc.hasDefault ? blankToNull(tblDefaults.get(rc.colName)) : null;
                LengthUnit unit = "C".equalsIgnoreCase(rc.charUsed) ? LengthUnit.CHAR
                        : ("B".equalsIgnoreCase(rc.charUsed) ? LengthUnit.BYTE : LengthUnit.NONE);
                Integer effectiveLen = unit == LengthUnit.CHAR && rc.charLen != null && rc.charLen > 0 ? rc.charLen : rc.dataLen;
                boolean unboundedNumber = ("NUMBER".equalsIgnoreCase(rc.dataType) || "NUMERIC".equalsIgnoreCase(rc.dataType))
                        && (rc.prec == null || rc.prec <= 0);

                String nativeDisplay = formatOracleNativeType(rc.dataType, effectiveLen, unit, rc.prec, rc.scale, unboundedNumber);
                var norm = CanonicalTypeNormalizer.normalize(
                        sourceDialect, rc.dataType, null, effectiveLen, unit, rc.prec, rc.scale, unboundedNumber, false);
                var def = (rc.isIdentity || rc.isVirtual)
                        ? new CanonicalTypeNormalizer.NormalizedDefault(CanonicalSchema.DefaultExprKind.NONE, null)
                        : CanonicalTypeNormalizer.normalizeDefault(dataDefault);
                IdentitySpec identity = rc.isIdentity ? new IdentitySpec(false, 1, 1) : null;
                String generatedExpr = rc.isVirtual ? dataDefault : null;

                cols.add(new CanonicalColumn(
                        rc.colName, norm.kind(), nativeDisplay, norm.length(), norm.lengthUnit(),
                        norm.precision(), norm.scale(), norm.unboundedDecimal(),
                        norm.unsigned(), norm.national(), norm.fractionalSeconds(),
                        rc.nullable, def.kind(), def.raw(), identity, generatedExpr, tblComments.get(rc.colName)
                ));
            }
            result.put(tableName, cols);
        }
        return result;
    }

    private boolean readRawColumns(
            Connection connection,
            boolean ownSchema,
            String owner,
            Set<String> tableFilter,
            String sql,
            boolean hasRichCols,
            Map<String, List<RawColumn>> out,
            int[] defaultColCount
    ) {
        try (PreparedStatement ps = prepareScoped(connection, sql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString(1);
                if (!tableFilter.contains(tableName)) continue;
                String colName = rs.getString(2);
                String dataType = rs.getString(3);
                Integer dataLen = getIntByIndex(rs, 4);
                Integer charLen = getIntByIndex(rs, 5);
                String charUsed = rs.getString(6);
                Integer prec = getIntByIndex(rs, 7);
                Integer scale = getIntByIndex(rs, 8);
                boolean nullable = !"N".equalsIgnoreCase(rs.getString(9));
                Integer defLen = getIntByIndex(rs, 10);
                boolean isIdentity = hasRichCols && "YES".equalsIgnoreCase(rs.getString(11));
                boolean isVirtual = hasRichCols && "YES".equalsIgnoreCase(rs.getString(12));
                boolean hasDefault = (defLen != null && defLen > 0) || isVirtual;
                if (hasDefault) defaultColCount[0]++;

                out.computeIfAbsent(tableName, k -> new ArrayList<>()).add(new RawColumn(
                        colName, dataType, dataLen, charLen, charUsed, prec, scale, nullable, hasDefault, isIdentity, isVirtual
                ));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Map<String, String>> loadColumnDefaults(
            Connection connection,
            boolean ownSchema,
            String owner,
            DbTargetDialect sourceDialect,
            Set<String> tableFilter,
            List<String> warnings
    ) {
        // 1. Oracle / OceanBase-Oracle 优先通过服务端只读 PL/SQL 临时 CLOB 一次性批量转换 LONG -> VARCHAR2，仅 1 次网络往返
        if (sourceDialect == DbTargetDialect.ORACLE || sourceDialect == DbTargetDialect.OCEANBASE_ORACLE) {
            Map<String, Map<String, String>> plsqlResult = loadDefaultsViaPlsql(connection, ownSchema, owner, tableFilter);
            if (plsqlResult != null) {
                return plsqlResult;
            }
        }
        // 2. 降级方案（达梦 DM8 或受限环境）：直查 DEFAULT_LENGTH IS NOT NULL，加安全上限防超大库卡死
        Map<String, Map<String, String>> map = new HashMap<>();
        String sql = ownSchema
                ? "SELECT DATA_DEFAULT, TABLE_NAME, COLUMN_NAME FROM USER_TAB_COLUMNS WHERE DEFAULT_LENGTH IS NOT NULL AND DEFAULT_LENGTH > 0"
                : "SELECT DATA_DEFAULT, TABLE_NAME, COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND DEFAULT_LENGTH IS NOT NULL AND DEFAULT_LENGTH > 0";
        int fetched = 0;
        boolean capped = false;
        try (PreparedStatement ps = prepareScoped(connection, sql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String def = blankToNull(rs.getString(1));
                String table = rs.getString(2);
                String col = rs.getString(3);
                if (!tableFilter.contains(table)) continue;
                if (def != null) {
                    map.computeIfAbsent(table, k -> new HashMap<>()).put(col, def);
                }
                if (++fetched >= FALLBACK_LONG_DEFAULT_CAP) {
                    capped = true;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (capped) {
            warnings.add("源库带默认值的列超过 " + FALLBACK_LONG_DEFAULT_CAP + " 个且未启用批量 PL/SQL 读取，部分列默认值已跳过以防评估超时。");
        }
        return map;
    }

    private Map<String, Map<String, String>> loadDefaultsViaPlsql(
            Connection connection,
            boolean ownSchema,
            String owner,
            Set<String> tableFilter
    ) {
        String sourceSelect = ownSchema
                ? "SELECT TABLE_NAME, COLUMN_NAME, DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE DEFAULT_LENGTH IS NOT NULL AND DEFAULT_LENGTH > 0 AND DEFAULT_LENGTH <= 2000"
                : "SELECT TABLE_NAME, COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND DEFAULT_LENGTH IS NOT NULL AND DEFAULT_LENGTH > 0 AND DEFAULT_LENGTH <= 2000";
        String plsql = """
                DECLARE
                  v_out CLOB;
                  v_buf VARCHAR2(32767) := '';
                  v_line VARCHAR2(4000);
                  v_def VARCHAR2(2000);
                BEGIN
                  DBMS_LOB.CREATETEMPORARY(v_out, TRUE, DBMS_LOB.CALL);
                  FOR r IN (%s) LOOP
                    v_def := REPLACE(REPLACE(SUBSTR(r.DATA_DEFAULT, 1, 1000), CHR(10), ' '), CHR(13), ' ');
                    v_line := r.TABLE_NAME || CHR(31) || r.COLUMN_NAME || CHR(31) || v_def || CHR(30);
                    IF LENGTHB(v_buf) + LENGTHB(v_line) > 30000 THEN
                      DBMS_LOB.WRITEAPPEND(v_out, LENGTH(v_buf), v_buf);
                      v_buf := v_line;
                    ELSE
                      v_buf := v_buf || v_line;
                    END IF;
                  END LOOP;
                  IF LENGTH(v_buf) > 0 THEN
                    DBMS_LOB.WRITEAPPEND(v_out, LENGTH(v_buf), v_buf);
                  END IF;
                  ? := v_out;
                END;
                """.formatted(sourceSelect);
        try (CallableStatement cs = connection.prepareCall(plsql)) {
            int outIdx = ownSchema ? 1 : 2;
            if (!ownSchema) {
                cs.setString(1, owner);
            }
            cs.registerOutParameter(outIdx, Types.CLOB);
            cs.execute();
            Clob clob = cs.getClob(outIdx);
            if (clob == null) return Map.of();
            try {
                Map<String, Map<String, String>> map = new HashMap<>();
                StringBuilder record = new StringBuilder(256);
                char[] buf = new char[16 * 1024];
                try (Reader reader = clob.getCharacterStream()) {
                    int n;
                    while ((n = reader.read(buf)) >= 0) {
                        for (int i = 0; i < n; i++) {
                            char c = buf[i];
                            if (c == '\u001E') {
                                parseDefaultRecord(record, tableFilter, map);
                                record.setLength(0);
                            } else {
                                record.append(c);
                            }
                        }
                    }
                    if (!record.isEmpty()) {
                        parseDefaultRecord(record, tableFilter, map);
                    }
                }
                return map;
            } finally {
                try { clob.free(); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void parseDefaultRecord(StringBuilder record, Set<String> tableFilter, Map<String, Map<String, String>> out) {
        int sep1 = record.indexOf("\u001F");
        if (sep1 <= 0) return;
        int sep2 = record.indexOf("\u001F", sep1 + 1);
        if (sep2 <= sep1) return;
        String table = record.substring(0, sep1);
        if (!tableFilter.contains(table)) return;
        String col = record.substring(sep1 + 1, sep2);
        String def = blankToNull(record.substring(sep2 + 1));
        if (def != null) {
            out.computeIfAbsent(table, k -> new HashMap<>()).put(col, def);
        }
    }

    private String formatOracleNativeType(String dataType, Integer len, LengthUnit unit, Integer prec, Integer scale, boolean unboundedNumber) {
        if (dataType == null) return "VARCHAR2(255)";
        String upper = dataType.toUpperCase(Locale.ROOT);
        if (upper.equals("NUMBER")) {
            if (unboundedNumber) return "NUMBER";
            return scale == null ? "NUMBER(" + prec + ")" : "NUMBER(" + prec + "," + scale + ")";
        }
        if ((upper.equals("VARCHAR2") || upper.equals("NVARCHAR2") || upper.equals("CHAR") || upper.equals("NCHAR")) && len != null && len > 0) {
            String suffix = unit == LengthUnit.CHAR && !upper.startsWith("N") ? " CHAR" : "";
            return upper + "(" + len + suffix + ")";
        }
        if (upper.equals("RAW") && len != null && len > 0) {
            return "RAW(" + len + ")";
        }
        return dataType;
    }

    private Map<String, ConstraintBundle> loadConstraints(
            Connection connection,
            boolean ownSchema,
            String owner,
            DatabaseDialect dialect,
            DbTargetDialect sourceDialect,
            Set<String> tableFilter,
            MigrationOptions options
    ) {
        if (tableFilter.isEmpty()) return Map.of();
        Map<String, ConstraintBundle> result = new HashMap<>();
        Map<String, TreeMap<Integer, String>> colsByConstraint = new HashMap<>();

        String colSql = ownSchema
                ? "SELECT CONSTRAINT_NAME, COLUMN_NAME, POSITION FROM USER_CONS_COLUMNS WHERE POSITION IS NOT NULL ORDER BY CONSTRAINT_NAME, POSITION"
                : "SELECT CONSTRAINT_NAME, COLUMN_NAME, POSITION FROM ALL_CONS_COLUMNS WHERE OWNER = ? AND POSITION IS NOT NULL ORDER BY CONSTRAINT_NAME, POSITION";
        try (PreparedStatement ps = prepareScoped(connection, colSql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Integer pos = getIntByIndex(rs, 3);
                if (pos == null) continue;
                colsByConstraint.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(pos, rs.getString(2));
            }
        } catch (Exception ignored) {
        }

        // 第 1 步：仅查 P/U/R 约束且绝不查 LONG 类型的 SEARCH_CONDITION（支持 fetchSize=2000 全速拉取）
        Map<String, String> constraintTable = new HashMap<>();
        String purSql = ownSchema
                ? """
                  SELECT TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE, R_OWNER, R_CONSTRAINT_NAME, DELETE_RULE, INDEX_NAME
                  FROM USER_CONSTRAINTS
                  WHERE CONSTRAINT_TYPE IN ('P','U','R')
                  ORDER BY TABLE_NAME, CONSTRAINT_NAME
                  """
                : """
                  SELECT TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE, R_OWNER, R_CONSTRAINT_NAME, DELETE_RULE, INDEX_NAME
                  FROM ALL_CONSTRAINTS
                  WHERE OWNER = ? AND CONSTRAINT_TYPE IN ('P','U','R')
                  ORDER BY TABLE_NAME, CONSTRAINT_NAME
                  """;
        List<RawConstraint> rawList = new ArrayList<>();
        try (PreparedStatement ps = prepareScoped(connection, purSql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                String name = rs.getString(2);
                String type = rs.getString(3);
                constraintTable.put(name, table);
                if (!tableFilter.contains(table)) continue;
                rawList.add(new RawConstraint(table, name, type, rs.getString(4), rs.getString(5),
                        rs.getString(6), null, rs.getString(7)));
            }
        } catch (Exception ignored) {
            return result;
        }

        for (RawConstraint rc : rawList) {
            ConstraintBundle bundle = result.computeIfAbsent(rc.table, k -> new ConstraintBundle());
            List<String> cols = colsByConstraint.containsKey(rc.name)
                    ? List.copyOf(colsByConstraint.get(rc.name).values()) : List.of();
            switch (rc.type) {
                case "P" -> {
                    bundle.pkName = rc.name;
                    bundle.pkIndexName = rc.indexName;
                    bundle.pkColumns.clear();
                    bundle.pkColumns.addAll(cols);
                }
                case "R" -> {
                    if (!options.foreignKeysEnabled()) break;
                    String refTable = constraintTable.get(rc.rConstraintName);
                    List<String> refCols = colsByConstraint.containsKey(rc.rConstraintName)
                            ? List.copyOf(colsByConstraint.get(rc.rConstraintName).values()) : List.of();
                    if (refTable != null && !cols.isEmpty() && !refCols.isEmpty()) {
                        String safeName = dialect.backupConstraintName(owner, rc.table, rc.name);
                        String deleteRule = rc.deleteRule == null || rc.deleteRule.isBlank() ? "NO ACTION" : rc.deleteRule.trim().toUpperCase(Locale.ROOT);
                        bundle.foreignKeys.add(new CanonicalForeignKey(
                                safeName, cols, rc.rOwner, refTable, refCols, "NO ACTION", deleteRule
                        ));
                    }
                }
                default -> { }
            }
        }

        // 第 2 步：用户自定义 CHECK 约束（仅 GENERATED = 'USER NAME'，跳过全部数以万计的系统 NOT NULL 约束；优先查 12c+ VARCHAR2 列 SEARCH_CONDITION_VC）
        if (options.checkConstraintsEnabled()) {
            loadUserCheckConstraints(connection, ownSchema, owner, tableFilter, result);
        }
        return result;
    }

    private void loadUserCheckConstraints(
            Connection connection,
            boolean ownSchema,
            String owner,
            Set<String> tableFilter,
            Map<String, ConstraintBundle> result
    ) {
        String vcSql = ownSchema
                ? "SELECT TABLE_NAME, CONSTRAINT_NAME, SEARCH_CONDITION_VC FROM USER_CONSTRAINTS WHERE CONSTRAINT_TYPE = 'C' AND GENERATED = 'USER NAME'"
                : "SELECT TABLE_NAME, CONSTRAINT_NAME, SEARCH_CONDITION_VC FROM ALL_CONSTRAINTS WHERE OWNER = ? AND CONSTRAINT_TYPE = 'C' AND GENERATED = 'USER NAME'";
        String legacySql = ownSchema
                ? "SELECT SEARCH_CONDITION, TABLE_NAME, CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE CONSTRAINT_TYPE = 'C' AND GENERATED = 'USER NAME'"
                : "SELECT SEARCH_CONDITION, TABLE_NAME, CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE OWNER = ? AND CONSTRAINT_TYPE = 'C' AND GENERATED = 'USER NAME'";
        try (PreparedStatement ps = prepareScoped(connection, vcSql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (!tableFilter.contains(table)) continue;
                String name = rs.getString(2);
                String cond = blankToNull(rs.getString(3));
                if (cond != null && !TRIVIAL_NOT_NULL.matcher(cond).matches()) {
                    result.computeIfAbsent(table, k -> new ConstraintBundle())
                            .checks.add(new CanonicalCheckConstraint(name, cond));
                }
            }
        } catch (Exception ignored) {
            // Oracle 11g / DM8 无 SEARCH_CONDITION_VC 列，回退到仅过滤 USER NAME 的 SEARCH_CONDITION
            try (PreparedStatement ps = prepareScoped(connection, legacySql, ownSchema, owner);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cond = blankToNull(rs.getString(1));
                    String table = rs.getString(2);
                    if (!tableFilter.contains(table)) continue;
                    String name = rs.getString(3);
                    if (cond != null && !TRIVIAL_NOT_NULL.matcher(cond).matches()) {
                        result.computeIfAbsent(table, k -> new ConstraintBundle())
                                .checks.add(new CanonicalCheckConstraint(name, cond));
                    }
                }
            } catch (Exception ignoredAgain) {
            }
        }
    }

    private Map<String, List<CanonicalIndex>> loadIndexes(
            Connection connection,
            boolean ownSchema,
            String owner,
            DatabaseDialect dialect,
            DbTargetDialect sourceDialect,
            Set<String> tableFilter,
            Map<String, ConstraintBundle> constraintsByTable
    ) {
        if (tableFilter.isEmpty()) return Map.of();
        Map<String, RawIndex> indexMap = new LinkedHashMap<>();
        String idxSql = ownSchema
                ? """
                  SELECT i.TABLE_NAME, i.INDEX_NAME, i.UNIQUENESS, i.INDEX_TYPE,
                         c.COLUMN_NAME, c.COLUMN_POSITION, c.DESCEND
                  FROM USER_INDEXES i
                  JOIN USER_IND_COLUMNS c ON c.INDEX_NAME = i.INDEX_NAME
                  WHERE i.INDEX_TYPE NOT IN ('LOB')
                  ORDER BY i.TABLE_NAME, i.INDEX_NAME, c.COLUMN_POSITION
                  """
                : """
                  SELECT i.TABLE_NAME, i.INDEX_NAME, i.UNIQUENESS, i.INDEX_TYPE,
                         c.COLUMN_NAME, c.COLUMN_POSITION, c.DESCEND
                  FROM ALL_INDEXES i
                  JOIN ALL_IND_COLUMNS c ON c.INDEX_OWNER = i.OWNER AND c.INDEX_NAME = i.INDEX_NAME
                  WHERE i.TABLE_OWNER = ? AND i.INDEX_TYPE NOT IN ('LOB')
                  ORDER BY i.TABLE_NAME, i.INDEX_NAME, c.COLUMN_POSITION
                  """;
        boolean hasFunctionBasedIndex = false;
        try (PreparedStatement ps = prepareScoped(connection, idxSql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (!tableFilter.contains(table)) continue;
                String idxName = rs.getString(2);
                boolean unique = "UNIQUE".equalsIgnoreCase(rs.getString(3));
                IndexKind kind = classifyOracleIndexKind(rs.getString(4));
                if (kind == IndexKind.EXPRESSION) hasFunctionBasedIndex = true;
                String colName = rs.getString(5);
                int pos = rs.getInt(6);
                boolean desc = "DESC".equalsIgnoreCase(rs.getString(7));
                RawIndex raw = indexMap.computeIfAbsent(table + "\u0000" + idxName,
                        k -> new RawIndex(table, idxName, unique, kind));
                raw.columns.put(pos, new IndexColumn(colName, desc, null, null));
            }
        } catch (Exception ignored) {
            return Map.of();
        }

        // 仅存在函数索引时才查询 LONG 类型的 IND_EXPRESSIONS，避免普通库多出额外扫表
        if (hasFunctionBasedIndex) {
            String exprSql = ownSchema
                    ? "SELECT COLUMN_EXPRESSION, INDEX_NAME, COLUMN_POSITION FROM USER_IND_EXPRESSIONS"
                    : "SELECT COLUMN_EXPRESSION, INDEX_NAME, COLUMN_POSITION FROM ALL_IND_EXPRESSIONS WHERE INDEX_OWNER = ?";
            Map<String, Map<Integer, String>> exprs = new HashMap<>();
            try (PreparedStatement ps = prepareScoped(connection, exprSql, ownSchema, owner);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String expr = blankToNull(rs.getString(1));
                    if (expr != null) {
                        exprs.computeIfAbsent(rs.getString(2), k -> new HashMap<>()).put(rs.getInt(3), expr);
                    }
                }
            } catch (Exception ignored) {
            }
            for (RawIndex raw : indexMap.values()) {
                Map<Integer, String> idxExprs = exprs.get(raw.name);
                if (idxExprs == null || idxExprs.isEmpty()) continue;
                for (var e : idxExprs.entrySet()) {
                    IndexColumn old = raw.columns.get(e.getKey());
                    raw.columns.put(e.getKey(), new IndexColumn(null, old != null && old.descending(), null, e.getValue()));
                }
            }
        }

        Map<String, List<CanonicalIndex>> result = new HashMap<>();
        for (RawIndex raw : indexMap.values()) {
            ConstraintBundle cb = constraintsByTable.getOrDefault(raw.table, ConstraintBundle.EMPTY);
            if (cb.pkName != null && cb.pkName.equalsIgnoreCase(raw.name)) continue;
            if (cb.pkIndexName != null && cb.pkIndexName.equalsIgnoreCase(raw.name)) continue;
            List<IndexColumn> cols = List.copyOf(raw.columns.values());
            if (isPrimaryBackingIndex(cb.pkColumns, raw.unique, cols)) continue;

            String portableName = raw.name;
            if (sourceDialect == DbTargetDialect.DAMENG) {
                try {
                    var resolved = dialect.backupIndexName(connection, owner, raw.table, raw.name);
                    if (resolved.isEmpty()) continue;
                    portableName = resolved.get();
                } catch (Exception ignored) {
                }
            }
            result.computeIfAbsent(raw.table, k -> new ArrayList<>())
                    .add(new CanonicalIndex(portableName, cols, raw.unique, raw.kind, null));
        }
        return result;
    }

    private List<CanonicalSequence> loadSequences(Connection connection, boolean ownSchema, String owner, List<String> warnings) {
        List<CanonicalSequence> list = new ArrayList<>();
        String sql = ownSchema
                ? "SELECT SEQUENCE_NAME, LAST_NUMBER, INCREMENT_BY, MIN_VALUE, MAX_VALUE, CYCLE_FLAG, CACHE_SIZE FROM USER_SEQUENCES ORDER BY SEQUENCE_NAME"
                : "SELECT SEQUENCE_NAME, LAST_NUMBER, INCREMENT_BY, MIN_VALUE, MAX_VALUE, CYCLE_FLAG, CACHE_SIZE FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = ? ORDER BY SEQUENCE_NAME";
        try (PreparedStatement ps = prepareScoped(connection, sql, ownSchema, owner);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name == null || name.startsWith("ISEQ$$_")) continue; // Oracle 内部 IDENTITY 隐藏序列
                list.add(new CanonicalSequence(
                        name,
                        safeBigLong(rs, 2, 1L),
                        safeBigLong(rs, 3, 1L),
                        safeNullableBigLong(rs, 4),
                        safeNullableBigLong(rs, 5),
                        "Y".equalsIgnoreCase(rs.getString(6)),
                        safeNullableBigLong(rs, 7),
                        null,
                        null
                ));
            }
        } catch (Exception error) {
            warnings.add("读取序列列表失败（已跳过）：" + shortMessage(error));
        }
        return list;
    }

    private static Integer getIntByIndex(ResultSet rs, int index) {
        try {
            Object val = rs.getObject(index);
            return val instanceof Number n ? n.intValue() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long safeBigLong(ResultSet rs, int idx, long fallback) {
        try {
            var bd = rs.getBigDecimal(idx);
            if (bd == null) return fallback;
            if (bd.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return Long.MAX_VALUE;
            if (bd.compareTo(java.math.BigDecimal.valueOf(Long.MIN_VALUE)) < 0) return Long.MIN_VALUE;
            return bd.longValue();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Long safeNullableBigLong(ResultSet rs, int idx) {
        try {
            var bd = rs.getBigDecimal(idx);
            if (bd == null) return null;
            if (bd.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return Long.MAX_VALUE;
            if (bd.compareTo(java.math.BigDecimal.valueOf(Long.MIN_VALUE)) < 0) return Long.MIN_VALUE;
            return bd.longValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static IndexKind classifyOracleIndexKind(String indexType) {
        if (indexType == null) return IndexKind.BTREE;
        String upper = indexType.toUpperCase(Locale.ROOT);
        if (upper.contains("BITMAP")) return IndexKind.BITMAP;
        if (upper.contains("FUNCTION-BASED")) return IndexKind.EXPRESSION;
        if (upper.contains("NORMAL") || upper.contains("CLUSTER")) return IndexKind.BTREE;
        return IndexKind.OTHER;
    }

    private record TableMeta(Long estimatedRows, boolean partitioned) {
    }

    private record RawColumn(String colName, String dataType, Integer dataLen, Integer charLen, String charUsed,
                             Integer prec, Integer scale, boolean nullable, boolean hasDefault,
                             boolean isIdentity, boolean isVirtual) {
    }

    private record RawConstraint(String table, String name, String type, String rOwner, String rConstraintName,
                                 String deleteRule, String searchCondition, String indexName) {
    }

    private static final class ConstraintBundle {
        static final ConstraintBundle EMPTY = new ConstraintBundle();
        String pkName;
        String pkIndexName;
        final List<String> pkColumns = new ArrayList<>();
        final List<CanonicalForeignKey> foreignKeys = new ArrayList<>();
        final List<CanonicalCheckConstraint> checks = new ArrayList<>();
    }

    private static final class RawIndex {
        final String table;
        final String name;
        final boolean unique;
        final IndexKind kind;
        final TreeMap<Integer, IndexColumn> columns = new TreeMap<>();

        RawIndex(String table, String name, boolean unique, IndexKind kind) {
            this.table = table;
            this.name = name;
            this.unique = unique;
            this.kind = kind;
        }
    }
}
