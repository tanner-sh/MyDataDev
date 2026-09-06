import type { ConnectionPermission } from './accessControl';

/**
 * 管理抽屉的分区定义。
 *
 * 纯数据 + 纯判断，放在组件外面：哪些分区在什么条件下可用，是一条能单测的规则，
 * 不该埋在 JSX 的三元表达式里。
 */
export type ManagementSection = 'connections' | 'backups' | 'scheduled-exports' | 'schema-diff' | 'schema-snapshots' | 'data-diff' | 'data-search' | 'data-transfer' | 'mcp' | 'ai' | 'sessions' | 'audit' | 'users' | 'access';

/**
 * 分区的分组。
 *
 * 分区从 5 个长到 14 个之后，平铺的一列已经不能靠扫一眼找到东西了 —— 「数据对比」和
 * 「审计日志」挨在一起只是因为它们是先后加进来的。分组本身是产品判断（这件事属于哪一类），
 * 所以和分区清单放在一起，而不是在 JSX 里按下标切片。
 */
export type ManagementGroup = 'operations' | 'data' | 'integrations' | 'governance';

export const MANAGEMENT_GROUPS: ReadonlyArray<{ key: ManagementGroup; label: string }> = [
  { key: 'operations', label: '连接与运维' },
  { key: 'data', label: '数据与结构' },
  { key: 'integrations', label: '智能与集成' },
  { key: 'governance', label: '安全与治理' }
];

export type ManagementSectionMeta = {
  key: ManagementSection;
  label: string;
  group: ManagementGroup;
  /** 需要先选中一条连接才有意义的分区。 */
  requiresConnection?: boolean;
  requiresAdmin?: boolean;
  /** 仅 Web 多用户模式有意义；桌面/认证关闭模式不显示。 */
  requiresAuthentication?: boolean;
  requiresPermission?: ConnectionPermission;
};

/**
 * 清单顺序即导航顺序，同组的分区必须连着写 —— `managementNavigation` 不做重排，
 * 它只在组变了的时候插一条组标题。第一项还兼任「请求的分区不可用时退回哪里」的答案，
 * 所以连接管理必须排在最前（它是唯一一个没有任何前置条件的分区）。
 */
export const MANAGEMENT_SECTIONS: ManagementSectionMeta[] = [
  { key: 'connections', label: '连接管理', group: 'operations' },
  { key: 'backups', label: '备份与恢复', group: 'operations', requiresConnection: true, requiresPermission: 'BACKUP_RESTORE' },
  // 定时导出把数据带出系统，与手动导出同档：EXPORT 之外还要 QUERY。
  { key: 'scheduled-exports', label: '定时导出', group: 'operations', requiresPermission: 'EXPORT' },
  { key: 'sessions', label: '活动会话', group: 'operations', requiresConnection: true, requiresPermission: 'VIEW_METADATA' },
  { key: 'schema-diff', label: '结构对比', group: 'data' },
  // 快照与漂移只读元数据，与结构对比同档。
  { key: 'schema-snapshots', label: '结构快照', group: 'data', requiresPermission: 'VIEW_METADATA' },
  // 数据对比要把两侧的业务数据整批读出来，所以要的是 QUERY 而不是 VIEW_METADATA。
  { key: 'data-diff', label: '数据对比', group: 'data', requiresPermission: 'QUERY' },
  // 检索读的是业务数据本身，所以要 QUERY 而不是 VIEW_METADATA。
  { key: 'data-search', label: '数据检索', group: 'data', requiresPermission: 'QUERY' },
  // 传输把数据搬出源连接，与导出同档。两端的真正授权在服务端逐条连接校验（源端 EXPORT +
  // QUERY、目标端 DATA_WRITE）—— 这里只是入口的可见性，因为面板里选的连接未必是当前选中的这条。
  { key: 'data-transfer', label: '数据传输', group: 'data', requiresPermission: 'EXPORT' },
  { key: 'mcp', label: 'MCP Server', group: 'integrations', requiresAdmin: true },
  { key: 'ai', label: 'AI 助手', group: 'integrations', requiresAdmin: true },
  { key: 'audit', label: '审计日志', group: 'governance', requiresAdmin: true },
  { key: 'users', label: '用户与权限', group: 'governance', requiresAdmin: true, requiresAuthentication: true },
  { key: 'access', label: '访问控制', group: 'governance', requiresAdmin: true, requiresAuthentication: true }
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

/**
 * 导航要渲染的东西：一串组，每组带着这一次真正可见的分区。
 *
 * 整组都不可见时（桌面模式下的「安全与治理」只剩审计，认证关闭时用户与访问控制都不显示）
 * 不留空标题 —— 一个下面什么都没有的分组名比没有分组更让人困惑。
 */
export function managementNavigation(
  isAdmin = true,
  authenticationEnabled = true
): Array<{ key: ManagementGroup; label: string; sections: ManagementSectionMeta[] }> {
  return MANAGEMENT_GROUPS
    .map((group) => ({
      ...group,
      sections: MANAGEMENT_SECTIONS.filter((section) => section.group === group.key
        && isManagementSectionVisible(section.key, isAdmin, authenticationEnabled))
    }))
    .filter((group) => group.sections.length > 0);
}
