import { describe, expect, it } from 'vitest';
import {
  captureQuery,
  captureResultMessage,
  driftAuditNotice,
  driftStatusLabel,
  driftSummary,
  EMPTY_SNAPSHOT_FORM,
  formatMoment,
  scheduleFormOf,
  scheduleSummary,
  snapshotLabel,
  snapshotPeriod
} from './schemaSnapshot';
import type { SchemaDriftResponse, SchemaSnapshotSummary, SchemaSnapshotTarget } from './types';

const snapshot = (overrides: Partial<SchemaSnapshotSummary> = {}): SchemaSnapshotSummary => ({
  id: 7,
  connectionId: 1,
  schemaName: 'PUBLIC',
  label: '上线前',
  tableCount: 12,
  checksum: 'abc',
  capturedAt: '2026-09-01T03:00:00Z',
  lastSeenAt: '2026-09-01T03:00:00Z',
  ...overrides
});

const drift = (overrides: Partial<SchemaDriftResponse> = {}): SchemaDriftResponse => ({
  baseline: snapshot(),
  targetLabel: '当前结构',
  summary: { onlyInSource: 0, onlyInTarget: 0, different: 0, identical: 12 },
  tables: [],
  auditEvents: [],
  warnings: [],
  ...overrides
});

describe('captureQuery', () => {
  it('只带上填了的字段，空 Schema 交给服务端解析', () => {
    expect(captureQuery({ ...EMPTY_SNAPSHOT_FORM, connectionId: 3 })).toBe('connectionId=3');
    expect(captureQuery({ connectionId: 3, schemaName: ' shop ', label: ' 上线前 ' }))
      .toBe('connectionId=3&schemaName=shop&label=%E4%B8%8A%E7%BA%BF%E5%89%8D');
  });
});

describe('snapshotPeriod', () => {
  /** 同一时刻就是一个时刻，不要写成一段假的区间。 */
  it('采集时间与最后确认时间相同时只显示一个时刻', () => {
    expect(snapshotPeriod(snapshot())).toBe(formatMoment('2026-09-01T03:00:00Z'));
  });

  /** 「这个结构从 A 一直保持到 B」—— 显示成单个时刻会让人以为中间还发生过别的事。 */
  it('不同则显示成一段区间', () => {
    const period = snapshotPeriod(snapshot({ lastSeenAt: '2026-09-05T03:00:00Z' }));
    expect(period).toContain('起，最后一次确认于');
  });
});

describe('captureResultMessage', () => {
  it('结构没变时说清楚没有新增记录', () => {
    const message = captureResultMessage({ snapshot: snapshot(), changed: false, warnings: [] });
    expect(message).toContain('没有新增记录');
  });

  it('结构变了时给出表数量', () => {
    expect(captureResultMessage({ snapshot: snapshot(), changed: true, warnings: [] })).toContain('12 张表');
  });
});

describe('driftSummary', () => {
  /** 空白会被读成「还没比」，没有变化时必须明确说出来。 */
  it('没有变化时明确说没有变化', () => {
    expect(driftSummary(drift())).toContain('结构没有变化');
  });

  it('把三种变化分开数', () => {
    expect(driftSummary(drift({ summary: { onlyInSource: 1, onlyInTarget: 2, different: 3, identical: 4 } })))
      .toBe('与「上线前」相比：新增 2 张表，删除 1 张表，3 张表结构改动。');
  });

  it('只有一种变化时不留下空的分句', () => {
    expect(driftSummary(drift({ summary: { onlyInSource: 0, onlyInTarget: 1, different: 0, identical: 4 } })))
      .toBe('与「上线前」相比：新增 1 张表。');
  });

  it('没有标签的快照用编号称呼', () => {
    expect(snapshotLabel(snapshot({ label: '  ' }))).toBe('快照 7');
  });
});

describe('driftStatusLabel', () => {
  /** 方向按时间读：后来才有的是「新增」，而不是「只在目标端」。 */
  it('按时间方向命名而不是按对比方向', () => {
    expect(driftStatusLabel({ tableName: 't', status: 'ONLY_IN_TARGET', items: [] })).toBe('新增');
    expect(driftStatusLabel({ tableName: 't', status: 'ONLY_IN_SOURCE', items: [] })).toBe('删除');
    expect(driftStatusLabel({ tableName: 't', status: 'DIFFERENT', items: [] })).toBe('结构改动');
  });
});

describe('driftAuditNotice', () => {
  /**
   * 这条是整个功能里最容易被读错的一句：审计只记得到经由本工具的操作，而漂移多半来自
   * 别的客户端或发布流水线。说成「没人改过」会让用户据此得出完全错误的结论。
   */
  it('没有审计记录时说的是「不是从这里改的」而不是「没人改过」', () => {
    const notice = driftAuditNotice(drift());
    expect(notice).toContain('不是从这里发起');
    expect(notice).toContain('不代表没人改过');
  });

  it('有记录时也点明库外的改动不在其中', () => {
    const notice = driftAuditNotice(drift({
      auditEvents: [{ at: '2026-09-02T01:00:00Z', action: 'OBJECT_CREATE', actor: 'tanner' }]
    }));
    expect(notice).toContain('1 次');
    expect(notice).toContain('库外的改动不在其中');
  });
});

describe('定期采集', () => {
  const target: SchemaSnapshotTarget = {
    id: 1, connectionId: 1, schemaName: 'PUBLIC', cron: '0 0 3 * * *', zoneId: 'Asia/Shanghai',
    enabled: true, keepSnapshots: 30
  };

  it('没有任务时提醒手动采集只留下按按钮的时刻', () => {
    expect(scheduleSummary(undefined)).toContain('手动采集');
  });

  it('有任务时给出 cron、时区与保留份数', () => {
    expect(scheduleSummary(target)).toContain('0 0 3 * * *');
    expect(scheduleSummary(target)).toContain('Asia/Shanghai');
    expect(scheduleSummary(target)).toContain('保留最近 30 份');
  });

  it('上次失败时标出来', () => {
    expect(scheduleSummary({ ...target, lastRunAt: '2026-09-05T19:00:00Z', lastStatus: 'FAILED' }))
      .toContain('失败');
  });

  it('表单从已有任务回填，没有任务则用默认值', () => {
    expect(scheduleFormOf(target).cron).toBe('0 0 3 * * *');
    expect(scheduleFormOf(undefined).keepSnapshots).toBe(30);
  });
});

describe('formatMoment', () => {
  it('解析不了的值原样返回，不猜', () => {
    expect(formatMoment('not a date')).toBe('not a date');
    expect(formatMoment(null)).toBe('—');
  });
});
