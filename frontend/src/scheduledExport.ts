import type { ScheduledExportTask } from './types';

/**
 * 定时导出的纯逻辑。
 *
 * cron 的编辑与描述直接复用 `backupSchedule.ts` —— 定时这件事此前只服务备份一件事，但
 * 「每天几点」「按什么时区算」是同一套东西，再造一套只会让两处的行为慢慢分叉。这里只放
 * 导出特有的部分：格式、状态解读、保存前的校验。
 */

export const SCHEDULED_EXPORT_FORMATS: { value: string; label: string }[] = [
  { value: 'csv', label: 'CSV' },
  { value: 'xlsx', label: 'Excel (xlsx)' },
  { value: 'json', label: 'JSON' },
  { value: 'sql', label: 'SQL INSERT' },
  { value: 'xml', label: 'XML' },
  { value: 'markdown', label: 'Markdown' }
];

export type ScheduledExportFormValues = {
  connectionId?: number;
  name?: string;
  sql?: string;
  exportFormat?: string;
  cron?: string;
  scheduleZone?: string;
  enabled?: boolean;
  productionConfirmation?: string;
};

/**
 * 保存前的校验。
 *
 * 只判断前端能判断的：SQL 是不是单条查询由服务端说了算（那条规则在 `ExportService`
 * 里只有一份定义），这里再实现一遍必然分叉。
 */
export function validateScheduledExport(
  values: ScheduledExportFormValues,
  connection?: { name: string; environment?: string }
): string | null {
  if (!values.connectionId) return '请选择一条连接。';
  if (!values.name?.trim()) return '请填写任务名。';
  if (!values.sql?.trim()) return '请填写要定时执行的查询。';
  const cron = values.cron?.trim();
  if (!cron) return '请设置执行周期 —— 定时导出没有「仅手动」这一档。';
  if (cron.split(/\s+/).length !== 6) return 'cron 需要 6 段（秒 分 时 日 月 周）。';
  if (values.exportFormat && !SCHEDULED_EXPORT_FORMATS.some((item) => item.value === values.exportFormat)) {
    return `不支持的导出格式：${values.exportFormat}`;
  }
  // 定时任务没有交互确认的机会，那次确认在创建时完成；跳过它等于给生产库开了一个
  // 无人值守的出口。
  if (connection?.environment === 'prod' && values.productionConfirmation?.trim() !== connection.name) {
    return `这是生产连接，请输入连接名「${connection.name}」以确认。`;
  }
  return null;
}

/** 上次运行的状态标签。从没跑过和跑失败了是两回事，不要都显示成灰色。 */
export function scheduledExportStatus(task: Pick<ScheduledExportTask, 'enabled' | 'lastStatus'>): {
  color: string;
  label: string;
} {
  if (task.lastStatus === 'FAILED') return { color: 'red', label: '上次失败' };
  if (task.lastStatus === 'SUCCESS') return { color: task.enabled ? 'green' : 'default', label: '上次成功' };
  return { color: 'default', label: '尚未执行' };
}

/**
 * 下次执行时间的说明。
 *
 * 停用的任务不显示时间 —— 那会让人以为它还在跑；cron 解析不了时也说清楚，而不是留空。
 */
export function nextRunLabel(task: Pick<ScheduledExportTask, 'enabled' | 'cron'>, nextRunAt?: string | null): string {
  if (!task.enabled) return '已停用';
  if (!nextRunAt) return task.cron?.trim() ? 'cron 无法解析' : '未设置周期';
  return new Date(nextRunAt).toLocaleString('zh-CN', { hour12: false });
}
