import { describe, expect, it } from 'vitest';
import {
  filterCommands,
  groupCommands,
  moveCommandSelection,
  rememberCommand,
  scoreCommand,
  type PaletteCommand
} from './commandPalette';

const commands: PaletteCommand[] = [
  { id: 'sql.new', title: '新建 SQL 标签页', section: 'SQL', keywords: 'new sql tab', hint: 'Alt+N' },
  { id: 'sql.history', title: 'SQL 执行历史', section: 'SQL', keywords: 'history' },
  { id: 'manage.backups', title: '备份与恢复', section: '管理', keywords: 'backup restore' },
  { id: 'manage.audit', title: '审计日志', section: '管理', keywords: 'audit log', disabledReason: '需要管理员权限' }
];

describe('scoreCommand', () => {
  it('前缀优于包含，标题优于关键词', () => {
    const title = commands[1];
    expect(scoreCommand(title, 'SQL')).toBe(100);
    expect(scoreCommand(commands[0], 'SQL')).toBe(80);
    expect(scoreCommand(commands[2], 'backup')).toBe(70);
  });

  /** 「bk」也该找到 backup，但不能把打了完整词的人挤下去。 */
  it('子序列匹配排在直接包含之后', () => {
    expect(scoreCommand(commands[2], 'bkp')).toBe(30);
    expect(scoreCommand(commands[2], 'zzz')).toBeNull();
  });

  it('空检索词全部保留', () => {
    expect(scoreCommand(commands[0], '   ')).toBe(0);
  });
});

describe('filterCommands', () => {
  it('按匹配质量排序', () => {
    expect(filterCommands(commands, 'sql').map((command) => command.id))
      .toEqual(['sql.history', 'sql.new']);
  });

  /** 回车会落在第一条上，所以按不动的命令一律沉底。 */
  it('不可用的命令沉到最后但仍然列出', () => {
    const ordered = filterCommands(commands, '');
    expect(ordered).toHaveLength(4);
    expect(ordered[ordered.length - 1].id).toBe('manage.audit');
  });

  it('最近用过的靠前', () => {
    expect(filterCommands(commands, '', ['manage.backups'])[0].id).toBe('manage.backups');
  });

  /** 没有检索词时，清单的声明顺序本身就是分组，不该被标题长短重排打散。 */
  it('空检索词保持声明顺序', () => {
    expect(filterCommands(commands, '').map((command) => command.id))
      .toEqual(['sql.new', 'sql.history', 'manage.backups', 'manage.audit']);
  });
});

describe('groupCommands', () => {
  it('分区顺序跟着排序结果走', () => {
    const groups = groupCommands(filterCommands(commands, '', ['manage.backups']));
    expect(groups.map((group) => group.section)).toEqual(['管理', 'SQL']);
    expect(groups[0].commands[0].id).toBe('manage.backups');
  });
});

describe('moveCommandSelection', () => {
  const ordered = filterCommands(commands, '');

  it('两端循环', () => {
    expect(moveCommandSelection(-1, ordered.length, 1, ordered)).toBe(0);
    expect(moveCommandSelection(0, ordered.length, -1, ordered)).toBe(2);
  });

  /** 停在一个按不动的条目上没有意义。 */
  it('跳过不可用的条目', () => {
    expect(moveCommandSelection(2, ordered.length, 1, ordered)).toBe(0);
  });

  it('全部不可用时不选中任何一条', () => {
    const disabled = filterCommands([commands[3]], '');
    expect(moveCommandSelection(-1, disabled.length, 1, disabled)).toBe(-1);
  });
});

describe('rememberCommand', () => {
  it('去重、最新在前、有上限', () => {
    expect(rememberCommand(['b', 'a'], 'a')).toEqual(['a', 'b']);
    expect(rememberCommand(['a', 'b', 'c', 'd', 'e'], 'f')).toEqual(['f', 'a', 'b', 'c', 'd']);
  });
});
