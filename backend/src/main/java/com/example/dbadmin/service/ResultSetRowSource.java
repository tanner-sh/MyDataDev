package com.example.dbadmin.service;

import com.example.dbadmin.core.CellSerializer;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 以另一条连接的查询结果作为导入行来源。
 *
 * <p>跨连接传输复用的就是文件导入那条管线：列匹配、批量 INSERT 生成、转义与落盘全在
 * {@link DataImportService#convert} 里，这里只负责「怎么读出一行」。不经 CSV 中转有两个实
 * 打实的好处 —— 用户不必把生产数据落一份明文到磁盘上，而且 {@link ResultSet#wasNull()} 说得
 * 准，空字符串不会在往返一趟之后变成 NULL（见 {@link #distinguishesNull()}）。</p>
 *
 * <p>第一次 {@link #readRow()} 返回的是列标签行，与 CSV 的表头对齐 —— 于是目标表的列匹配、
 * 「表头里的列在目标表中不存在」这类报错都不用为传输再写一份。列名对不上时的解法也很自然：
 * 在源 SQL 里写 {@code AS 目标列名}。</p>
 */
final class ResultSetRowSource implements ImportRowSource {
    /**
     * 单元格文本上限。
     *
     * <p>与导出那条路的取值不同：导出截断了还能看出「后面还有」，而传输截断等于往目标库写进
     * 一个残缺的值，事后没人分得清哪些行被截过。所以这里超限直接失败，不截断。</p>
     */
    private static final int MAX_CELL_CHARS = 16 * 1024 * 1024;

    private final ResultSet rs;
    private final int columnCount;
    private final List<String> header;
    private boolean headerSent;
    private long rows;

    ResultSetRowSource(ResultSet rs) throws Exception {
        this.rs = rs;
        ResultSetMetaData metadata = rs.getMetaData();
        this.columnCount = metadata.getColumnCount();
        if (columnCount == 0) throw new IllegalArgumentException("源查询没有返回任何列。");
        List<String> unsupported = new ArrayList<>();
        List<String> labels = new ArrayList<>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            String label = metadata.getColumnLabel(index);
            labels.add(label);
            if (isBinary(metadata.getColumnType(index), metadata.getColumnTypeName(index))) unsupported.add(label);
        }
        if (!unsupported.isEmpty()) {
            // 二进制没有可移植的脚本字面量写法，硬编成十六进制在各家数据库上的语法都不一样。
            // 与其写进去一个各库解释不同的值，不如现在就说清楚该怎么绕开。
            throw new IllegalArgumentException(
                    "二进制字段无法跨连接传输：" + String.join("、", unsupported)
                            + "。请在源查询里去掉这些列，或改用备份恢复。"
            );
        }
        this.header = List.copyOf(labels);
    }

    /** 传输了多少数据行（不含列标签行）。 */
    long rowCount() {
        return rows;
    }

    @Override
    public String label() {
        return "源查询结果";
    }

    /** {@code ResultSet.wasNull()} 分得清空串和 NULL，所以这条路不把空串当 NULL。 */
    @Override
    public boolean distinguishesNull() {
        return true;
    }

    @Override
    public List<String> readRow() throws Exception {
        if (!headerSent) {
            headerSent = true;
            return header;
        }
        // 取消是通过中断工作线程发出的：一份千万行的结果集在这里可能跑很久，不看中断标记
        // 的话「取消」要等到整份脚本写完才生效，那时数据已经全落到磁盘上了。
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("跨连接传输已取消。");
        if (!rs.next()) return null;
        List<String> row = new ArrayList<>(columnCount);
        for (int index = 1; index <= columnCount; index++) row.add(cell(index));
        rows++;
        return row;
    }

    private String cell(int index) throws Exception {
        Object value = rs.getObject(index);
        if (value == null || rs.wasNull()) return null;
        if (value instanceof Clob clob) {
            long length = clob.length();
            if (length > MAX_CELL_CHARS) {
                throw new IllegalArgumentException(
                        "第 " + (rows + 1) + " 行的 " + header.get(index - 1) + " 超过 " + MAX_CELL_CHARS
                                + " 字符，无法安全地写进传输脚本。"
                );
            }
            return length == 0 ? "" : clob.getSubString(1, (int) length);
        }
        // 布尔统一写成 1/0：它在各家的落点完全不同（MySQL 是 TINYINT、PostgreSQL 是 boolean、
        // SQL Server 是 BIT），而 '1' 这个字符串字面量是唯一四家都接受的写法 —— 'TRUE' 在
        // MySQL 的 TINYINT 列上会直接报错。
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        // 其余一律走 CellSerializer 的文本规则：同一个值在结果表格、执行计划和这里必须长得
        // 一样，否则「导过去的时间列多了个 .0」这种问题查起来毫无线索。
        String text = CellSerializer.text(value);
        if (text.length() > MAX_CELL_CHARS) {
            throw new IllegalArgumentException(
                    "第 " + (rows + 1) + " 行的 " + header.get(index - 1) + " 超过 " + MAX_CELL_CHARS
                            + " 字符，无法安全地写进传输脚本。"
            );
        }
        return text;
    }

    /**
     * 判定一列是不是二进制。
     *
     * <p>只看 {@link Types} 不够：不少驱动把自己的二进制类型报成 {@code OTHER}，类型名才带
     * BLOB/BINARY 字样。判定与 {@code DataDiffService} 跳过二进制列的那条同源。</p>
     */
    private static boolean isBinary(int jdbcType, String typeName) {
        if (jdbcType == Types.BINARY || jdbcType == Types.VARBINARY || jdbcType == Types.LONGVARBINARY
                || jdbcType == Types.BLOB) {
            return true;
        }
        String upper = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        return upper.contains("BLOB") || upper.contains("BINARY") || upper.contains("BYTEA")
                || upper.contains("IMAGE") || upper.contains("RAW");
    }

    @Override
    public void close() {
        // ResultSet 的生命周期挂在打开它的 Statement/Connection 上，由 DataTransferService 的
        // try-with-resources 收拾；这里关掉会让那边的关闭顺序多一个例外情况。
    }
}
