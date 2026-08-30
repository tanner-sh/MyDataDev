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
  createdAt: string;
  updatedAt: string;
};

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
