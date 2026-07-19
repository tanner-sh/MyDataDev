import type { SqlFileExecution } from './types';

export function sqlFileTaskPercent(job: SqlFileExecution) {
  if (job.status === 'READY' || job.status === 'SUCCESS') return 100;
  if (job.status === 'ANALYZING') return job.fileSize ? Math.min(99, Math.round(job.processedBytes * 100 / job.fileSize)) : 0;
  if (job.statementTotal) return Math.min(99, Math.round(job.statementCurrent * 100 / job.statementTotal));
  return 0;
}

export function sqlFileStatusLabel(status: SqlFileExecution['status']) {
  return ({ ANALYZING: '解析中', READY: '等待确认', QUEUED: '排队中', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消', EXPIRED: '已过期' } as const)[status];
}

export function formatSqlFileBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`;
}
