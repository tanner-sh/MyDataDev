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

    /** 读下一行；读完返回 {@code null}。 */
    List<String> readRow() throws Exception;

    @Override
    void close() throws Exception;
}
