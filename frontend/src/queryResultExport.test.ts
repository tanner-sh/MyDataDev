import { describe, expect, it } from 'vitest';
import { exportFileExtension, inferSqlTargetParts, parseQualifiedTableName, readResultCopyFormat, serializeCopiedRows, serializeQueryResult, writeResultCopyFormat } from './queryResultExport';

const columns = [
  { key: 'c1', label: 'id', typeName: 'INTEGER' },
  { key: 'c2', label: 'name', typeName: 'VARCHAR' }
];

describe('query result target table', () => {
  it('infers only one simple top-level source', () => {
    expect(inferSqlTargetParts('select * from app.users')).toEqual(['app', 'users']);
    expect(inferSqlTargetParts('select * from users u join roles r on r.id=u.role_id')).toBeUndefined();
    expect(inferSqlTargetParts('with q as (select * from users) select * from q')).toBeUndefined();
    expect(inferSqlTargetParts('select * from (select * from users) q')).toBeUndefined();
  });

  it('falls back to JDBC source metadata when SQL text is unavailable', () => {
    expect(inferSqlTargetParts(undefined, { nameParts: ['public', 'users'] })).toEqual(['public', 'users']);
  });

  it('parses quoted target names and rejects SQL fragments', () => {
    expect(parseQualifiedTableName('"My Schema"."User"')).toEqual(['My Schema', 'User']);
    expect(parseQualifiedTableName('[dbo].[Order]')).toEqual(['dbo', 'Order']);
    expect(parseQualifiedTableName('users where true')).toBeUndefined();
  });
});

describe('query result serialization', () => {
  it('exports CSV, JSON and XML from the supplied rows', () => {
    const rows = [[1, 'Alice, "A"']];
    expect(serializeQueryResult('csv', columns, rows)).toContain('"Alice, ""A"""');
    expect(JSON.parse(serializeQueryResult('json', columns, rows)).rows).toEqual(rows);
    expect(serializeQueryResult('xml', columns, [[1, '<Alice>']])).toContain('&lt;Alice&gt;');
  });

  it('uses the actual target and database quoting in SQL inserts', () => {
    expect(serializeQueryResult('sql', columns, [[1, "O'Reilly"]], {
      dbType: 'mysql',
      targetTableParts: ['app', 'users']
    })).toBe("INSERT INTO `app`.`users` (`id`, `name`) VALUES (1, 'O''Reilly');\n");
    expect(() => serializeQueryResult('sql', columns, [[1, 'Alice']])).toThrow(/目标表/);
  });

  it('uses database-specific string, boolean, and binary literals', () => {
    const dialectColumns = [
      { key: 'c1', label: 'path', typeName: 'VARCHAR' },
      { key: 'c2', label: 'active', typeName: 'BOOLEAN' },
      { key: 'c3', label: 'payload', typeName: 'VARBINARY' }
    ];
    expect(serializeQueryResult('sql', dialectColumns, [['a\\b', true, new Uint8Array([0, 255])]], {
      dbType: 'mysql',
      targetTableParts: ['files']
    })).toContain("VALUES (_utf8mb4 0x615c62, 1, 0x00ff)");
    expect(serializeQueryResult('sql', columns, [[1, '中文']], {
      dbType: 'sqlserver',
      targetTableParts: ['users']
    })).toContain("VALUES (1, N'中文')");
  });

  it('refuses to turn omitted result cells into corrupt SQL data', () => {
    const binaryColumns = [{ key: 'payload', label: 'payload', typeName: 'BLOB' }];
    expect(() => serializeQueryResult('sql', binaryColumns, [['<BLOB 1024 bytes>']], {
      dbType: 'mysql',
      targetTableParts: ['files']
    })).toThrow(/导出全部结果/);
    const textColumns = [{ key: 'note', label: 'note', typeName: 'CLOB' }];
    expect(() => serializeQueryResult('sql', textColumns, [['prefix… <CLOB 已截断，共 999999 字符>']], {
      dbType: 'oracle',
      targetTableParts: ['notes']
    })).toThrow(/导出全部结果/);
  });

  it('copies pipe data without a header and escapes separators', () => {
    expect(serializeCopiedRows('pipe', columns, [[1, 'A|B'], [2, null]])).toBe('1|A\\|B\n2|NULL');
  });
});

describe('copy format preference', () => {
  it('defaults to pipe and persists SQL format', () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value)
    };
    expect(readResultCopyFormat(storage)).toBe('pipe');
    writeResultCopyFormat('sql', storage);
    expect(readResultCopyFormat(storage)).toBe('sql');
  });
});

describe('Markdown 表格导出', () => {
  it('数值列右对齐，文本列左对齐', () => {
    const output = serializeQueryResult('markdown', columns, [[1, 'a']]);
    const [, alignment] = output.split('\n');
    // 对齐规则与结果表格一致：数值右对齐才能对位比较大小。
    expect(alignment).toBe('| ---: | --- |');
  });

  it('表头、分隔行、数据行的结构正确', () => {
    const output = serializeQueryResult('markdown', columns, [[1, 'a'], [2, 'b']]);
    expect(output).toBe('| id | name |\n| ---: | --- |\n| 1 | a |\n| 2 | b |\n');
  });

  it('结果为空时仍然输出表头', () => {
    // 贴出去的人至少要能看到查了哪些列。
    expect(serializeQueryResult('markdown', columns, [])).toBe('| id | name |\n| ---: | --- |\n');
  });

  it('竖线必须转义，否则整行列数被撑乱', () => {
    const output = serializeQueryResult('markdown', columns, [[1, 'a|b']]);
    expect(output).toContain('| 1 | a\\|b |');
    // 转义后这一行仍然只有两列。
    const dataRow = output.split('\n')[2];
    expect(dataRow.split(/(?<!\\)\|/).filter((part) => part !== '').length).toBe(2);
  });

  it('换行折成 <br> 而不是丢弃', () => {
    const output = serializeQueryResult('markdown', columns, [[1, 'line1\nline2']]);
    expect(output).toContain('line1<br>line2');
    // Markdown 表格的单元格不能跨行：数据行必须仍是一行。
    expect(output.split('\n')).toHaveLength(4);
  });

  it('null 渲染成空单元格', () => {
    expect(serializeQueryResult('markdown', columns, [[null, null]])).toContain('|  |  |');
  });
});

describe('导出文件扩展名', () => {
  it('Markdown 用惯例的 .md', () => {
    expect(exportFileExtension('markdown')).toBe('md');
  });

  it('其余格式名本身就是扩展名', () => {
    expect(exportFileExtension('csv')).toBe('csv');
    expect(exportFileExtension('json')).toBe('json');
    expect(exportFileExtension('xlsx')).toBe('xlsx');
  });
});
