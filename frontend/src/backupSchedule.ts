export type BackupScheduleKind = 'MANUAL' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'ADVANCED';
export type CronWeekday = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN';

export type BackupScheduleFields = {
  scheduleKind: BackupScheduleKind;
  scheduleTime?: string;
  weeklyDays?: CronWeekday[];
  monthlyDay?: string;
  advancedCron?: string;
};

export const WEEKDAY_OPTIONS: { value: CronWeekday; label: string }[] = [
  { value: 'MON', label: '周一' },
  { value: 'TUE', label: '周二' },
  { value: 'WED', label: '周三' },
  { value: 'THU', label: '周四' },
  { value: 'FRI', label: '周五' },
  { value: 'SAT', label: '周六' },
  { value: 'SUN', label: '周日' }
];

const WEEKDAY_ALIASES: Record<string, CronWeekday> = {
  '0': 'SUN',
  '1': 'MON',
  '2': 'TUE',
  '3': 'WED',
  '4': 'THU',
  '5': 'FRI',
  '6': 'SAT',
  '7': 'SUN',
  MON: 'MON',
  TUE: 'TUE',
  WED: 'WED',
  THU: 'THU',
  FRI: 'FRI',
  SAT: 'SAT',
  SUN: 'SUN'
};

export function scheduleFieldsFromCron(value?: string | null): BackupScheduleFields {
  const cron = value?.trim();
  if (!cron) return { scheduleKind: 'MANUAL', scheduleTime: '02:00', weeklyDays: ['MON'], monthlyDay: '1', advancedCron: '' };

  const fields = cron.split(/\s+/);
  if (fields.length !== 6 || fields[0] !== '0' || !validMinute(fields[1]) || !validHour(fields[2])) {
    return advancedSchedule(cron);
  }

  const time = `${fields[2].padStart(2, '0')}:${fields[1].padStart(2, '0')}`;
  if (fields[3] === '*' && fields[4] === '*' && fields[5] === '*') {
    return { scheduleKind: 'DAILY', scheduleTime: time, weeklyDays: ['MON'], monthlyDay: '1', advancedCron: '' };
  }

  if (fields[3] === '*' && fields[4] === '*' && fields[5] !== '*') {
    const days = parseWeekdays(fields[5]);
    if (days) {
      return { scheduleKind: 'WEEKLY', scheduleTime: time, weeklyDays: days, monthlyDay: '1', advancedCron: '' };
    }
  }

  if (fields[4] === '*' && fields[5] === '*' && validMonthlyDay(fields[3])) {
    return { scheduleKind: 'MONTHLY', scheduleTime: time, weeklyDays: ['MON'], monthlyDay: fields[3].toUpperCase(), advancedCron: '' };
  }

  return advancedSchedule(cron);
}

export function cronFromSchedule(fields: BackupScheduleFields): string {
  if (fields.scheduleKind === 'MANUAL') return '';
  if (fields.scheduleKind === 'ADVANCED') return fields.advancedCron?.trim() || '';

  const { hour, minute } = parseTime(fields.scheduleTime);
  if (fields.scheduleKind === 'DAILY') return `0 ${minute} ${hour} * * *`;
  if (fields.scheduleKind === 'WEEKLY') {
    const days = sortWeekdays(fields.weeklyDays || []);
    return days.length ? `0 ${minute} ${hour} * * ${days.join(',')}` : '';
  }
  const day = fields.monthlyDay?.toUpperCase();
  return day && validMonthlyDay(day) ? `0 ${minute} ${hour} ${day} * *` : '';
}

export function describeBackupSchedule(cron?: string | null): string {
  const parsed = scheduleFieldsFromCron(cron);
  switch (parsed.scheduleKind) {
    case 'MANUAL':
      return '仅手动执行';
    case 'DAILY':
      return `每天 ${parsed.scheduleTime}`;
    case 'WEEKLY':
      return `每周${(parsed.weeklyDays || []).map(weekdayLabel).join('、')} ${parsed.scheduleTime}`;
    case 'MONTHLY':
      return `每月${parsed.monthlyDay === 'L' ? '最后一天' : `${parsed.monthlyDay} 日`} ${parsed.scheduleTime}`;
    default:
      return `高级 Cron：${parsed.advancedCron}`;
  }
}

export function isAdvancedCron(cron?: string | null): boolean {
  return Boolean(cron?.trim()) && scheduleFieldsFromCron(cron).scheduleKind === 'ADVANCED';
}

export function isSixFieldCron(cron?: string | null): boolean {
  return Boolean(cron?.trim()) && cron!.trim().split(/\s+/).length === 6;
}

function advancedSchedule(cron: string): BackupScheduleFields {
  return { scheduleKind: 'ADVANCED', scheduleTime: '02:00', weeklyDays: ['MON'], monthlyDay: '1', advancedCron: cron };
}

function parseTime(value?: string) {
  const match = /^(\d{2}):(\d{2})$/.exec(value || '');
  if (!match || Number(match[1]) > 23 || Number(match[2]) > 59) {
    return { hour: '', minute: '' };
  }
  return { hour: String(Number(match[1])), minute: String(Number(match[2])) };
}

function validMinute(value: string) {
  return /^\d{1,2}$/.test(value) && Number(value) >= 0 && Number(value) <= 59;
}

function validHour(value: string) {
  return /^\d{1,2}$/.test(value) && Number(value) >= 0 && Number(value) <= 23;
}

function validMonthlyDay(value: string) {
  return value.toUpperCase() === 'L' || (/^\d{1,2}$/.test(value) && Number(value) >= 1 && Number(value) <= 31);
}

function parseWeekdays(value: string): CronWeekday[] | null {
  const tokens = value.toUpperCase().split(',');
  if (!tokens.length || tokens.some((token) => !WEEKDAY_ALIASES[token])) return null;
  return sortWeekdays(tokens.map((token) => WEEKDAY_ALIASES[token]));
}

function sortWeekdays(days: CronWeekday[]) {
  const order = WEEKDAY_OPTIONS.map((option) => option.value);
  return [...new Set(days)].sort((left, right) => order.indexOf(left) - order.indexOf(right));
}

function weekdayLabel(day: CronWeekday) {
  return WEEKDAY_OPTIONS.find((option) => option.value === day)?.label || day;
}

/** 常见时区兜底：浏览器不支持 Intl.supportedValuesOf 时仍要能选到时区。 */
const FALLBACK_TIME_ZONES = [
  'UTC',
  'Asia/Shanghai',
  'Asia/Hong_Kong',
  'Asia/Taipei',
  'Asia/Tokyo',
  'Asia/Singapore',
  'Asia/Seoul',
  'Asia/Kolkata',
  'Europe/London',
  'Europe/Berlin',
  'Europe/Moscow',
  'America/New_York',
  'America/Chicago',
  'America/Los_Angeles',
  'Australia/Sydney'
];

export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

export function supportedTimeZones(): string[] {
  const supported = (Intl as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  try {
    const values = supported?.('timeZone');
    return values?.length ? values : FALLBACK_TIME_ZONES;
  } catch {
    return FALLBACK_TIME_ZONES;
  }
}

/**
 * 时区下拉选项。浏览器时区排在最前并标注出来，任务上已保存但不在列表里的时区也要保留，
 * 否则编辑旧任务时会看到一个空的下拉框。
 */
export function timeZoneOptions(
  selected?: string,
  browser: string = browserTimeZone(),
  available: string[] = supportedTimeZones()
): { value: string; label: string }[] {
  const pinned = [browser, ...(selected && selected !== browser ? [selected] : [])];
  const rest = available.filter((zone) => !pinned.includes(zone)).sort();
  return [...pinned, ...rest].map((zone) => ({
    value: zone,
    label: zone === browser ? `${zone}（浏览器时区）` : zone
  }));
}

/**
 * 旧任务没有记录执行时区，一直按应用服务器的默认时区触发；服务器与浏览器不在同一时区时，
 * 设置的 02:00 实际会在本地的另一个时间点执行。这里给出提示，告诉用户重新保存即可修正。
 */
export function legacyScheduleZoneHint(
  task: { cron?: string; scheduleZone?: string; zoneId?: string },
  browser: string = browserTimeZone()
): string {
  if (!task.cron?.trim() || task.scheduleZone?.trim()) return '';
  const effective = task.zoneId?.trim();
  if (!effective || effective === browser) return '';
  return `该任务仍按服务端时区 ${effective} 触发；编辑并保存后将改用 ${browser}。`;
}
