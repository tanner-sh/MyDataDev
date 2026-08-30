import { describe, expect, it } from 'vitest';
import { groupMemberOptions, hasAnyConnectionPermission, hasConnectionPermission } from './accessControl';

describe('connection permission awareness', () => {
  it('桌面连接保持兼容，Web 连接按权限精确启用功能', () => {
    expect(hasConnectionPermission({}, 'DDL')).toBe(true);
    expect(hasConnectionPermission({ permissions: ['QUERY'] }, 'QUERY')).toBe(true);
    expect(hasConnectionPermission({ permissions: ['QUERY'] }, 'DATA_WRITE')).toBe(false);
    expect(hasAnyConnectionPermission({ permissions: ['DDL'] }, ['QUERY', 'DATA_WRITE', 'DDL'])).toBe(true);
  });

  it('连接管理员隐含全部权限', () => {
    expect(hasConnectionPermission({ permissions: ['CONNECTION_ADMIN'] }, 'BACKUP_RESTORE')).toBe(true);
  });
});

describe('用户组成员选项', () => {
  const users = [
    { id: 1, username: 'manual', displayName: '手工成员' },
    { id: 2, username: 'synced', displayName: '同步成员' }
  ];

  it('SSO 同步来的成员禁止在这里增删，并标出来源', () => {
    expect(groupMemberOptions(users, [2])).toEqual([
      { value: 1, label: '手工成员 (manual)', disabled: false },
      { value: 2, label: '同步成员 (synced) · SSO 同步', disabled: true }
    ]);
  });

  it('没有外部成员时所有人都可选', () => {
    expect(groupMemberOptions(users, []).every((option) => !option.disabled)).toBe(true);
  });
});
