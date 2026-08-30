import { describe, expect, it } from 'vitest';
import {
  auditActionColor,
  auditActionLabel,
  auditChainTag,
  auditExportNotice,
  auditPageSummary,
  auditRequestParams,
  auditTargetLabel,
  AUDIT_PAGE_SIZE,
  INITIAL_AUDIT_QUERY,
  isDangerousAuditAction,
  parseAuditConnectionId
} from './auditLog';

describe('auditActionLabel', () => {
  it('translates the codes the backend actually writes', () => {
    expect(auditActionLabel('SQL_EXECUTE_SCRIPT')).toBe('执行 SQL 脚本');
    expect(auditActionLabel('DATA_COMMIT')).toBe('提交表数据变更');
    expect(auditActionLabel('MCP_SQL_QUERY')).toBe('MCP 查询');
  });

  it('falls back to the raw code so a new action is never invisible', () => {
    expect(auditActionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(auditActionColor('SOMETHING_NEW')).toBeUndefined();
    expect(isDangerousAuditAction('SOMETHING_NEW')).toBe(false);
  });

  it('marks the destructive actions', () => {
    expect(isDangerousAuditAction('TABLE_DROP')).toBe(true);
    expect(isDangerousAuditAction('CONNECTION_DELETE')).toBe(true);
    expect(isDangerousAuditAction('SQL_EXPLAIN')).toBe(false);
  });
});

describe('parseAuditConnectionId', () => {
  it('reads the id out of both target shapes', () => {
    expect(parseAuditConnectionId('connection:7')).toBe(7);
    expect(parseAuditConnectionId('connection:7 table:users')).toBe(7);
  });

  it('does not confuse a longer id with a prefix match', () => {
    expect(parseAuditConnectionId('connection:70')).toBe(70);
  });

  it('returns undefined for targets that are not connection scoped', () => {
    expect(parseAuditConnectionId('prod-main')).toBeUndefined();
    expect(parseAuditConnectionId('')).toBeUndefined();
    expect(parseAuditConnectionId(null)).toBeUndefined();
    expect(parseAuditConnectionId('connection:abc')).toBeUndefined();
  });
});

describe('auditTargetLabel', () => {
  it('prefers the connection name when one is known', () => {
    expect(auditTargetLabel('connection:7', '本地商城库')).toBe('本地商城库');
    expect(auditTargetLabel('connection:7 table:users', '本地商城库')).toBe('本地商城库 · table:users');
  });

  it('falls back to the id for deleted connections', () => {
    expect(auditTargetLabel('connection:7')).toBe('连接 #7');
    // 连接删掉之后名字只剩 subject 里这一份，得留着。
    expect(auditTargetLabel('connection:7 本地商城库')).toBe('连接 #7 · 本地商城库');
  });

  it('连接自身的增删改不重复显示连接名', () => {
    // CONNECTION_CREATE/UPDATE/DELETE 把连接名写进了 subject；连接还在时前端已经解析出
    // 同一个名字，再拼一次就成了「X · X」。
    expect(auditTargetLabel('connection:7 本地商城库', '本地商城库')).toBe('本地商城库');
  });

  it('passes through targets it does not recognise', () => {
    expect(auditTargetLabel('storage-profile:3')).toBe('storage-profile:3');
    expect(auditTargetLabel(null)).toBe('—');
  });
});

describe('auditRequestParams', () => {
  it('sends only page and pageSize when nothing is filtered', () => {
    expect(auditRequestParams(INITIAL_AUDIT_QUERY)).toBe(`page=0&pageSize=${AUDIT_PAGE_SIZE}`);
  });

  it('omits blank keywords and includes the filters that are set', () => {
    const params = new URLSearchParams(auditRequestParams({
      keyword: '  users ', actor: 'admin', action: 'DATA_COMMIT', connectionId: 7, page: 2
    }));
    expect(params.get('keyword')).toBe('users');
    expect(params.get('actor')).toBe('admin');
    expect(params.get('action')).toBe('DATA_COMMIT');
    expect(params.get('connectionId')).toBe('7');
    expect(params.get('page')).toBe('2');
    expect(new URLSearchParams(auditRequestParams({ keyword: '   ', page: 0 })).has('keyword')).toBe(false);
  });

  it('never sends a negative page', () => {
    expect(new URLSearchParams(auditRequestParams({ keyword: '', page: -3 })).get('page')).toBe('0');
  });
});

describe('auditPageSummary', () => {
  it('never implies records that are not there', () => {
    expect(auditPageSummary(0, 0, false)).toBe('没有匹配的审计记录');
    expect(auditPageSummary(50, 0, true)).toBe('第 1-50 条，还有更早的记录');
    expect(auditPageSummary(12, 1, false)).toBe('第 51-62 条，已是最后一页');
  });
});

describe('审计链状态标签', () => {
  it('校验失败时指出出错位置', () => {
    expect(auditChainTag({ valid: false, checkedEvents: 12, firstInvalidId: 13, complete: true }))
      .toEqual({ color: 'red', label: '审计链异常', tooltip: '第 13 条附近校验失败' });
  });

  it('没验到表尾时不能报成完整', () => {
    const tag = auditChainTag({ valid: true, checkedEvents: 200000, complete: false });
    expect(tag.color).toBe('gold');
    expect(tag.label).toBe('审计链部分校验');
    expect(tag.tooltip).toContain('未到达最新记录');
  });

  it('验到表尾才是完整', () => {
    expect(auditChainTag({ valid: true, checkedEvents: 42, complete: true }))
      .toEqual({ color: 'green', label: '审计链完整', tooltip: '已校验 42 条事件' });
  });
});

describe('审计导出截断提示', () => {
  it('未截断时不打扰用户', () => {
    expect(auditExportNotice('120', 'false')).toBeNull();
    expect(auditExportNotice(null, null)).toBeNull();
  });

  it('截断时说清楚只导出了多少条', () => {
    expect(auditExportNotice('10000', 'true')).toContain('只包含最新的 10000 条记录');
  });

  it('行数缺失也要给出提示', () => {
    expect(auditExportNotice(null, 'true')).toContain('单次上限');
  });
});
