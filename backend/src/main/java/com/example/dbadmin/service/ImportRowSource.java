package com.example.dbadmin.service;

import java.util.List;

/**
 * 导入文件的行来源。
 *
 * <p>CSV 和 Excel 只有「怎么读出一行」不同，之后的列匹配、批量 INSERT 生成、转义与落盘
 * 完全一样。抽这个接口是为了让第二种格式不必复制一份生成逻辑 —— 那份逻辑里有转义和注释
 * 这两处安全约定，复制一份就等于多一处会漏掉的地方。</p>
 */
interface ImportRowSource extends AutoCloseable {
    /** 用在错误文案里的格式名，例如「CSV」「Excel」。 */
    String label();

    /** 读下一行；读完返回 {@code null}。元素为 {@code null} 表示这一格是 SQL NULL。 */
    List<String> readRow() throws Exception;

    /**
     * 这个来源能不能把「空字符串」和 NULL 区分开。
     *
     * <p>CSV 与 Excel 不能：文件里两者都是一个空单元格，猜哪一边都会错一半，所以那条路一律
     * 按 NULL 处理。跨连接传输能 —— {@code ResultSet.wasNull()} 说得准，而把空串写成 NULL
     * 是一次悄无声息的改数据：NOT NULL 列上会直接失败，可空列上则要等到有人对账才发现。</p>
     */
    default boolean distinguishesNull() {
        return false;
    }

    @Override
    void close() throws Exception;
}
