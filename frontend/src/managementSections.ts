import type { ConnectionPermission } from './accessControl';

/**
 * 管理抽屉的分区定义。
 *
 * 纯数据 + 纯判断，放在组件外面：哪些分区在什么条件下可用，是一条能单测的规则，
 * 不该埋在 JSX 的三元表达式里。
 */
export type ManagementSection = 'connections' | 'backups' | 'schema-diff' | 'mcp' | 'sessions' | 'audit' | 'users' | 'access';

export type ManagementSectionMeta = {
  key: ManagementSection;
  label: string;
  /** 需要先选中一条连接才有意义的分区。 */
  requiresConnection?: boolean;
  requiresAdmin?: boolean;
  /** 仅 Web 多用户模式有意义；桌面/认证关闭模式不显示。 */
  requiresAuthentication?: boolean;
  requiresPermission?: ConnectionPermission;
};

export const MANAGEMENT_SECTIONS: ManagementSectionMeta[] = [
  { key: 'connections', label: '连接管理' },
  { key: 'backups', label: '备份与恢复', requiresConnection: true, requiresPermission: 'BACKUP_RESTORE' },
  { key: 'schema-diff', label: '结构对比' },
  { key: 'mcp', label: 'MCP Server', requiresAdmin: true },
  { key: 'sessions', label: '活动会话', requiresConnection: true, requiresPermission: 'VIEW_METADATA' },
  { key: 'audit', label: '审计日志', requiresAdmin: true },
  { key: 'users', label: '用户与权限', requiresAdmin: true, requiresAuthentication: true },
  { key: 'access', label: '访问控制', requiresAdmin: true, requiresAuthentication: true }
];

export function managementSectionLabel(section: ManagementSection): string {
  return MANAGEMENT_SECTIONS.find((item) => item.key === section)?.label ?? '管理';
}

export function isManagementSectionVisible(section: ManagementSection, isAdmin = true, authenticationEnabled = true): boolean {
  const meta = MANAGEMENT_SECTIONS.find((item) => item.key === section);
  return Boolean(meta)
    && (!meta?.requiresAuthentication || authenticationEnabled)
    && (!meta?.requiresAdmin || !authenticationEnabled || isAdmin);
}

export function isManagementSectionAvailable(
  section: ManagementSection,
  hasConnection: boolean,
  isAdmin = true,
  authenticationEnabled = true,
  permissions?: ConnectionPermission[]
): boolean {
  const meta = MANAGEMENT_SECTIONS.find((item) => item.key === section);
  return isManagementSectionVisible(section, isAdmin, authenticationEnabled)
    && (!meta?.requiresConnection || hasConnection)
    && (!meta?.requiresPermission || permissions == null
      || permissions.includes(meta.requiresPermission) || permissions.includes('CONNECTION_ADMIN'));
}

/**
 * 打开抽屉时落在哪个分区。
 *
 * 没选连接时「备份」「会话」是不可用的，直接落上去只会看到一片禁用状态，
 * 所以退回第一个可用分区而不是硬开。
 */
export function resolveManagementSection(
  requested: ManagementSection,
  hasConnection: boolean,
  isAdmin = true,
  authenticationEnabled = true,
  permissions?: ConnectionPermission[]
): ManagementSection {
  if (isManagementSectionAvailable(requested, hasConnection, isAdmin, authenticationEnabled, permissions)) return requested;
  return MANAGEMENT_SECTIONS.find((item) => isManagementSectionAvailable(item.key, hasConnection, isAdmin, authenticationEnabled, permissions))?.key ?? 'connections';
}
