import { describe, expect, it } from 'vitest';
import { browserTimeZone, cronFromSchedule, describeBackupSchedule, isAdvancedCron, legacyScheduleZoneHint, scheduleFieldsFromCron, timeZoneOptions } from './backupSchedule';

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

describe('backup schedule time zones', () => {
  it('pins the browser zone first and keeps a saved zone that is not offered', () => {
    const options = timeZoneOptions('Pacific/Chatham', 'Asia/Shanghai', ['UTC', 'Asia/Shanghai', 'Europe/Berlin']);

    expect(options.map((option) => option.value)).toEqual(['Asia/Shanghai', 'Pacific/Chatham', 'Europe/Berlin', 'UTC']);
    expect(options[0].label).toBe('Asia/Shanghai（浏览器时区）');
    expect(options[2].label).toBe('Europe/Berlin');
  });

  it('does not duplicate the browser zone when it is the selected one', () => {
    const options = timeZoneOptions('Asia/Shanghai', 'Asia/Shanghai', ['Asia/Shanghai', 'UTC']);

    expect(options.map((option) => option.value)).toEqual(['Asia/Shanghai', 'UTC']);
  });

  it('reports a usable browser time zone', () => {
    expect(browserTimeZone()).toMatch(/^[A-Za-z]+(?:\/[A-Za-z0-9_+-]+)*$/);
  });
});

describe('legacy schedule zone hint', () => {
  it('warns only when an old task runs in a different zone than the browser', () => {
    expect(legacyScheduleZoneHint({ cron: '0 0 2 * * *', zoneId: 'Etc/UTC' }, 'Asia/Shanghai'))
      .toBe('该任务仍按服务端时区 Etc/UTC 触发；编辑并保存后将改用 Asia/Shanghai。');
    expect(legacyScheduleZoneHint({ cron: '0 0 2 * * *', zoneId: 'Asia/Shanghai' }, 'Asia/Shanghai')).toBe('');
    expect(legacyScheduleZoneHint({ cron: '0 0 2 * * *', scheduleZone: 'Etc/UTC', zoneId: 'Etc/UTC' }, 'Asia/Shanghai')).toBe('');
    expect(legacyScheduleZoneHint({ zoneId: 'Etc/UTC' }, 'Asia/Shanghai')).toBe('');
  });
});
