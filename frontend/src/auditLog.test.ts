import { describe, expect, it } from 'vitest';
import {
  auditActionColor,
  auditActionLabel,
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
