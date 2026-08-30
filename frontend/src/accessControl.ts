export type ConnectionPermission =
  | 'VIEW_METADATA'
  | 'QUERY'
  | 'DATA_WRITE'
  | 'DDL'
  | 'EXPORT'
  | 'BACKUP_RESTORE'
  | 'CONNECTION_ADMIN';

export const CONNECTION_PERMISSIONS: ConnectionPermission[] = [
  'VIEW_METADATA', 'QUERY', 'DATA_WRITE', 'DDL', 'EXPORT', 'BACKUP_RESTORE', 'CONNECTION_ADMIN'
];

export const CONNECTION_PERMISSION_LABELS: Record<ConnectionPermission, string> = {
  VIEW_METADATA: '查看元数据',
  QUERY: '执行查询',
  DATA_WRITE: '修改数据',
  DDL: '执行 DDL',
  EXPORT: '导出数据',
  BACKUP_RESTORE: '备份与恢复',
  CONNECTION_ADMIN: '管理连接（含全部权限）'
};

export function hasConnectionPermission(
  connection: { permissions?: ConnectionPermission[] } | null | undefined,
  permission: ConnectionPermission
): boolean {
  // 未启用 Web 认证的桌面模式没有 permissions 字段，保持原有全部可用行为。
  return connection?.permissions == null
    || connection.permissions.includes('CONNECTION_ADMIN')
    || connection.permissions.includes(permission);
}

export function hasAnyConnectionPermission(
  connection: { permissions?: ConnectionPermission[] } | null | undefined,
  permissions: ConnectionPermission[]
): boolean {
  return permissions.some((permission) => hasConnectionPermission(connection, permission));
}

export type UserGroup = {
  id: number;
  name: string;
  description?: string | null;
  memberUserIds: number[];
  /** memberUserIds 中由 SSO 同步而来的那部分，成员关系归身份提供器所有。 */
  externalMemberUserIds: number[];
  createdAt: string;
  updatedAt: string;
};

/**
 * 用户组成员下拉框的选项。
 *
 * <p>SSO 同步来的成员在这里是禁用的：后端保存时只替换手工成员关系，把这类成员从名单里去掉
 * 不会有任何效果（下次登录还会被同步回来），让它可勾可去只会让人以为改动生效了。要调整得去
 * 身份提供器那边改组。</p>
 */
export function groupMemberOptions(
  users: { id: number; username: string; displayName: string }[],
  externalMemberUserIds: number[]
): { value: number; label: string; disabled: boolean }[] {
  const external = new Set(externalMemberUserIds);
  return users.map((user) => ({
    value: user.id,
    label: external.has(user.id)
      ? `${user.displayName} (${user.username}) · SSO 同步`
      : `${user.displayName} (${user.username})`,
    disabled: external.has(user.id)
  }));
}

export type ConnectionGrant = {
  granteeType: 'USER' | 'GROUP';
  granteeId: number;
  permissions: ConnectionPermission[];
};

export type ConnectionAccessPolicy = {
  connectionId: number;
  accessMode: 'SHARED' | 'RESTRICTED';
  ownerUserId?: number | null;
  grants: ConnectionGrant[];
  availablePermissions: ConnectionPermission[];
};

export type PermissionTemplate = {
  key: string;
  name: string;
  description: string;
  permissions: ConnectionPermission[];
};
