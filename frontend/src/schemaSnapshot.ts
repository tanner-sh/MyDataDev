/**
 * 结构快照与漂移的纯逻辑。
 *
 * <p>这个功能唯一要回答的是「结构什么时候变的」，所以文案上有两条不能松的规矩：时间线上
 * 一条记录代表的是一段区间而不是一个瞬间（`capturedAt` 到 `lastSeenAt`），以及审计线索为空
 * 只说明「不是从这里改的」，绝不是「没人改过」—— 漂移多半来自本工具之外。</p>
 */
import type {
  SchemaDriftResponse,
  SchemaDriftTable,
  SchemaSnapshotCaptureResponse,
  SchemaSnapshotSummary,
  SchemaSnapshotTarget
} from './types';

export type SchemaSnapshotForm = {
  connectionId?: number;
  schemaName: string;
  label: string;
};

export type SnapshotScheduleForm = {
  cron: string;
  scheduleZone: string;
  enabled: boolean;
  keepSnapshots: number;
};

export const EMPTY_SNAPSHOT_FORM: SchemaSnapshotForm = { schemaName: '', label: '' };

export const DEFAULT_SNAPSHOT_SCHEDULE: SnapshotScheduleForm = {
  // 每天凌晨三点：发布通常在白天，夜里采到的是「今天最终的样子」。
  cron: '0 0 3 * * *',
  scheduleZone: '',
  enabled: true,
  keepSnapshots: 30
};

export function canCaptureSnapshot(form: SchemaSnapshotForm): boolean {
  return Boolean(form.connectionId);
}

export function captureQuery(form: SchemaSnapshotForm): string {
  const query = new URLSearchParams({ connectionId: String(form.connectionId) });
  if (form.schemaName.trim()) query.set('schemaName', form.schemaName.trim());
  if (form.label.trim()) query.set('label', form.label.trim());
  return query.toString();
}

export function snapshotLabel(snapshot: SchemaSnapshotSummary): string {
  return snapshot.label?.trim() || `快照 ${snapshot.id}`;
}

/**
 * 时间线上一条记录的说明。
 *
 * <p>采集时间与最后一次见到的时间相同时，说的就是一个时刻；不同则是一段区间 ——「这个结构
 * 从 A 一直保持到 B」。把区间显示成单个时刻，会让人以为中间还发生过别的事。</p>
 */
export function snapshotPeriod(snapshot: SchemaSnapshotSummary): string {
  const from = formatMoment(snapshot.capturedAt);
  const to = formatMoment(snapshot.lastSeenAt);
  return from === to ? from : `${from} 起，最后一次确认于 ${to}`;
}

export function captureResultMessage(response: SchemaSnapshotCaptureResponse): string {
  return response.changed
    ? `结构有变化，已记录新快照（${response.snapshot.tableCount} 张表）。`
    : `结构与上一份快照一致，没有新增记录，只更新了「最后一次确认」时间。`;
}

/** 漂移的一句话结论。没有变化时明确说出来，空白会被读成「还没比」。 */
export function driftSummary(drift: SchemaDriftResponse): string {
  const { onlyInSource, onlyInTarget, different } = drift.summary;
  if (onlyInSource + onlyInTarget + different === 0) {
    return `与「${snapshotLabel(drift.baseline)}」相比结构没有变化，共 ${drift.summary.identical} 张表。`;
  }
  const parts: string[] = [];
  if (onlyInTarget > 0) parts.push(`新增 ${onlyInTarget} 张表`);
  if (onlyInSource > 0) parts.push(`删除 ${onlyInSource} 张表`);
  if (different > 0) parts.push(`${different} 张表结构改动`);
  return `与「${snapshotLabel(drift.baseline)}」相比：${parts.join('，')}。`;
}

/** 表在漂移里的状态标签。方向按时间读：后来才有的是「新增」。 */
export function driftStatusLabel(table: SchemaDriftTable): string {
  if (table.status === 'ONLY_IN_TARGET') return '新增';
  if (table.status === 'ONLY_IN_SOURCE') return '删除';
  return '结构改动';
}

export function driftStatusColor(table: SchemaDriftTable): string {
  if (table.status === 'ONLY_IN_TARGET') return 'green';
  if (table.status === 'ONLY_IN_SOURCE') return 'red';
  return 'blue';
}

/**
 * 审计线索那一段的说明。
 *
 * <p>为空时必须说清楚是「不是从这里改的」而不是「没人改过」：审计只记得到经由本工具的操作，
 * 而结构漂移多半来自别的客户端或发布流水线。这句话说错，用户会据此得出一个完全错误的结论。</p>
 */
export function driftAuditNotice(drift: SchemaDriftResponse): string {
  if (drift.auditEvents.length > 0) {
    return `这段时间里本工具在这条连接上做过 ${drift.auditEvents.length} 次结构变更。库外的改动不在其中。`;
  }
  return '这段时间里本工具没有在这条连接上做过结构变更。这只说明改动不是从这里发起的，不代表没人改过。';
}

export function scheduleFormOf(target?: SchemaSnapshotTarget): SnapshotScheduleForm {
  if (!target) return { ...DEFAULT_SNAPSHOT_SCHEDULE };
  return {
    cron: target.cron,
    scheduleZone: target.scheduleZone || '',
    enabled: target.enabled,
    keepSnapshots: target.keepSnapshots
  };
}

export function scheduleSummary(target?: SchemaSnapshotTarget): string {
  if (!target) return '还没有设置定期采集。手动采集只留下你按下按钮的那些时刻。';
  const state = target.enabled ? '已启用' : '已停用';
  const last = target.lastRunAt ? `，上次 ${formatMoment(target.lastRunAt)}${target.lastStatus === 'FAILED' ? '（失败）' : ''}` : '';
  return `${state}：${target.cron}（${target.zoneId}），保留最近 ${target.keepSnapshots} 份${last}。`;
}

/** 把 ISO 时间串裁成「2026-09-06 03:00」这种长度。解析不了就原样返回，不猜。 */
export function formatMoment(value?: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}`
    + ` ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`;
}
