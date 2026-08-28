/**
 * 管理抽屉的分区定义。
 *
 * 纯数据 + 纯判断，放在组件外面：哪些分区在什么条件下可用，是一条能单测的规则，
 * 不该埋在 JSX 的三元表达式里。
 */
export type ManagementSection = 'connections' | 'backups' | 'schema-diff' | 'mcp' | 'sessions' | 'audit';

export type ManagementSectionMeta = {
  key: ManagementSection;
  label: string;
  /** 需要先选中一条连接才有意义的分区。 */
  requiresConnection?: boolean;
};

export const MANAGEMENT_SECTIONS: ManagementSectionMeta[] = [
  { key: 'connections', label: '连接管理' },
  { key: 'backups', label: '备份与恢复', requiresConnection: true },
  { key: 'schema-diff', label: '结构对比' },
  { key: 'mcp', label: 'MCP Server' },
  { key: 'sessions', label: '活动会话', requiresConnection: true },
  { key: 'audit', label: '审计日志' }
];

export function managementSectionLabel(section: ManagementSection): string {
  return MANAGEMENT_SECTIONS.find((item) => item.key === section)?.label ?? '管理';
}

export function isManagementSectionAvailable(section: ManagementSection, hasConnection: boolean): boolean {
  const meta = MANAGEMENT_SECTIONS.find((item) => item.key === section);
  return Boolean(meta) && (!meta?.requiresConnection || hasConnection);
}

/**
 * 打开抽屉时落在哪个分区。
 *
 * 没选连接时「备份」「会话」是不可用的，直接落上去只会看到一片禁用状态，
 * 所以退回第一个可用分区而不是硬开。
 */
export function resolveManagementSection(requested: ManagementSection, hasConnection: boolean): ManagementSection {
  if (isManagementSectionAvailable(requested, hasConnection)) return requested;
  return MANAGEMENT_SECTIONS.find((item) => !item.requiresConnection || hasConnection)?.key ?? 'connections';
}
