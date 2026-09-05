import { describe, expect, it } from 'vitest';
import { nextRunLabel, scheduledExportStatus, validateScheduledExport } from './scheduledExport';

const base = { connectionId: 1, name: '每日订单', sql: 'select 1', cron: '0 0 8 * * *', exportFormat: 'csv' };

describe('validateScheduledExport', () => {
  it('接受一条填全了的任务', () => {
    expect(validateScheduledExport(base, { name: '测试库', environment: 'dev' })).toBeNull();
  });

  it('拒绝空的必填项', () => {
    expect(validateScheduledExport({ ...base, name: '  ' })).toContain('任务名');
    expect(validateScheduledExport({ ...base, sql: '' })).toContain('查询');
    expect(validateScheduledExport({ ...base, connectionId: undefined })).toContain('连接');
  });

  /** 定时导出没有「仅手动」这一档：没有周期的任务永远不会跑，存下来只是个误会。 */
  it('要求一个能解析的六段 cron', () => {
    expect(validateScheduledExport({ ...base, cron: '' })).toContain('执行周期');
    expect(validateScheduledExport({ ...base, cron: '0 8 * * *' })).toContain('6 段');
  });

  it('拒绝不支持的导出格式', () => {
    expect(validateScheduledExport({ ...base, exportFormat: 'parquet' })).toContain('parquet');
  });

  /** 生产连接必须在创建时确认一次 —— 运行时没有人在场输入。 */
  it('生产连接上要求输入连接名', () => {
    const prod = { name: '生产库', environment: 'prod' };
    expect(validateScheduledExport(base, prod)).toContain('生产库');
    expect(validateScheduledExport({ ...base, productionConfirmation: '生产' }, prod)).toContain('生产库');
    expect(validateScheduledExport({ ...base, productionConfirmation: '生产库' }, prod)).toBeNull();
  });
});

describe('scheduledExportStatus', () => {
  it('把「没跑过」和「跑失败了」分开', () => {
    expect(scheduledExportStatus({ enabled: true, lastStatus: undefined }).label).toBe('尚未执行');
    expect(scheduledExportStatus({ enabled: true, lastStatus: 'FAILED' })).toEqual({ color: 'red', label: '上次失败' });
    expect(scheduledExportStatus({ enabled: true, lastStatus: 'SUCCESS' }).color).toBe('green');
  });

  /** 停用的任务即使上次成功也不该显示成绿色：它现在不跑了。 */
  it('停用后不再显示成功色', () => {
    expect(scheduledExportStatus({ enabled: false, lastStatus: 'SUCCESS' }).color).toBe('default');
  });
});

describe('nextRunLabel', () => {
  it('停用与解析失败各说各的', () => {
    expect(nextRunLabel({ enabled: false, cron: '0 0 8 * * *' }, '2026-09-06T00:00:00Z')).toBe('已停用');
    expect(nextRunLabel({ enabled: true, cron: 'not a cron' }, null)).toBe('cron 无法解析');
    expect(nextRunLabel({ enabled: true, cron: '' }, null)).toBe('未设置周期');
  });

  it('有下次执行时间时格式化出来', () => {
    expect(nextRunLabel({ enabled: true, cron: '0 0 8 * * *' }, '2026-09-06T00:00:00Z')).toMatch(/2026/);
  });
});
