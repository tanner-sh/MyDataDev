import { describe, expect, it } from 'vitest';
import { hasAnyConnectionPermission, hasConnectionPermission } from './accessControl';

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
