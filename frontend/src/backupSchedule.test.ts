import { describe, expect, it } from 'vitest';
import { cronFromSchedule, describeBackupSchedule, isAdvancedCron, scheduleFieldsFromCron } from './backupSchedule';

describe('backup schedule helpers', () => {
  it('generates Spring six-field cron expressions for friendly presets', () => {
    expect(cronFromSchedule({ scheduleKind: 'DAILY', scheduleTime: '02:30' })).toBe('0 30 2 * * *');
    expect(cronFromSchedule({ scheduleKind: 'WEEKLY', scheduleTime: '23:05', weeklyDays: ['SUN', 'MON', 'MON'] })).toBe('0 5 23 * * MON,SUN');
    expect(cronFromSchedule({ scheduleKind: 'MONTHLY', scheduleTime: '01:00', monthlyDay: 'L' })).toBe('0 0 1 L * *');
    expect(cronFromSchedule({ scheduleKind: 'MANUAL' })).toBe('');
  });

  it('recognizes generated presets and numeric weekdays', () => {
    expect(scheduleFieldsFromCron('0 5 23 * * MON,SUN')).toMatchObject({
      scheduleKind: 'WEEKLY',
      scheduleTime: '23:05',
      weeklyDays: ['MON', 'SUN']
    });
    expect(scheduleFieldsFromCron('0 0 8 * * 1,5')).toMatchObject({ scheduleKind: 'WEEKLY', weeklyDays: ['MON', 'FRI'] });
    expect(describeBackupSchedule('0 0 1 L * *')).toBe('每月最后一天 01:00');
  });

  it('keeps unrecognized expressions intact in advanced mode', () => {
    const cron = '0 */15 9-18 * * MON-FRI';
    expect(scheduleFieldsFromCron(cron)).toMatchObject({ scheduleKind: 'ADVANCED', advancedCron: cron });
    expect(cronFromSchedule(scheduleFieldsFromCron(cron))).toBe(cron);
    expect(isAdvancedCron(cron)).toBe(true);
  });

  it('describes an empty cron as a manual task', () => {
    expect(scheduleFieldsFromCron('')).toMatchObject({ scheduleKind: 'MANUAL' });
    expect(describeBackupSchedule(undefined)).toBe('仅手动执行');
  });
});
