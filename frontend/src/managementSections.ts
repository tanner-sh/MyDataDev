import type { ConnectionPermission } from './accessControl';

/**
 * 管理抽屉的分区定义。
 *
 * 纯数据 + 纯判断，放在组件外面：哪些分区在什么条件下可用，是一条能单测的规则，
 * 不该埋在 JSX 的三元表达式里。
 */
export type ManagementSection = 'connections' | 'backups' | 'pool' | 'server-info' | 'scheduled-exports' | 'schema-diff' | 'schema-snapshots' | 'schema-export' | 'table-relations' | 'data-diff' | 'data-search' | 'data-transfer' | 'mcp' | 'ai' | 'sessions' | 'audit' | 'users' | 'access';

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

/**
 * 导航入口：几个分区合成一个导航项，进去之后在顶部用标签切换。
 *
 * 分区数涨到 16 个之后，导航本身成了要找的东西。结构对比、结构快照、数据对比回答的是同一类
 * 问题（「两边 / 前后有什么不同」），用户与访问控制是同一件事的两半 —— 它们各占一行只是因为
 * 先后加进来。合并只发生在导航这一层：分区的 key、权限判断、命令面板里的「打开 xxx」都不变，
 * 所以 openManagement('data-diff') 这类调用照常落到对应的标签上。
 */
export type ManagementEntry = 'compare' | 'identity' | 'status' | 'export';

const MANAGEMENT_ENTRY_LABELS: Record<ManagementEntry, string> = {
  compare: '对比与漂移',
  identity: '用户与权限',
  // 「这个库现在怎么样」：会话、服务端版本与能力、连接池占用。连接池原来压在连接列表上面，
  // 服务端信息藏在连接卡片的「⋯」里，会话又单独一项 —— 回答的是同一个问题。
  status: '运行状态',
  // 把定义或数据带出这条连接的三条路。定时导出原来在「连接与运维」组，另两个在「数据与结构」组。
  export: '导出与传输'
};

export type ManagementSectionMeta = {
  key: ManagementSection;
  label: string;
  group: ManagementGroup;
  /** 归属的合并导航入口；不写就是自己单独一项。同一入口的分区在清单里必须连着写。 */
  entry?: ManagementEntry;
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
  { key: 'sessions', label: '活动会话', group: 'operations', entry: 'status', requiresConnection: true, requiresPermission: 'VIEW_METADATA' },
  // 服务端信息要连上去读版本与能力，与原先连接卡片菜单里的判定一致：要 QUERY。
  { key: 'server-info', label: '服务端信息', group: 'operations', entry: 'status', requiresConnection: true, requiresPermission: 'QUERY' },
  // 连接池是全局的（所有连接的池），不需要先选连接；调整设置的按钮在面板里另按管理员判定。
  { key: 'pool', label: '连接池', group: 'operations', entry: 'status' },
  { key: 'schema-diff', label: '结构对比', group: 'data', entry: 'compare' },
  // 快照与漂移只读元数据，与结构对比同档。
  { key: 'schema-snapshots', label: '结构快照', group: 'data', entry: 'compare', requiresPermission: 'VIEW_METADATA' },
  // 数据对比要把两侧的业务数据整批读出来，所以要的是 QUERY 而不是 VIEW_METADATA。
  { key: 'data-diff', label: '数据对比', group: 'data', entry: 'compare', requiresPermission: 'QUERY' },
  // 只读定义、只生成文本，与查看单个对象的 DDL 同档；执行仍要走 SQL 工作台那条路。
  { key: 'schema-export', label: '对象导出', group: 'data', entry: 'export', requiresPermission: 'VIEW_METADATA' },
  // 定时导出把数据带出系统，与手动导出同档：EXPORT 之外还要 QUERY。
  { key: 'scheduled-exports', label: '定时导出', group: 'data', entry: 'export', requiresPermission: 'EXPORT' },
  // 传输把数据搬出源连接，与导出同档。两端的真正授权在服务端逐条连接校验（源端 EXPORT +
  // QUERY、目标端 DATA_WRITE）—— 这里只是入口的可见性，因为面板里选的连接未必是当前选中的这条。
  { key: 'data-transfer', label: '数据传输', group: 'data', entry: 'export', requiresPermission: 'EXPORT' },
  // 表关系的候选来自执行历史（只给名字对），判断影响所有人的 JOIN 补全：与看历史同档，要 QUERY。
  { key: 'table-relations', label: '表关系', group: 'data', requiresPermission: 'QUERY' },
  // 检索读的是业务数据本身，所以要 QUERY 而不是 VIEW_METADATA。
  { key: 'data-search', label: '数据检索', group: 'data', requiresPermission: 'QUERY' },
  { key: 'mcp', label: 'MCP Server', group: 'integrations', requiresAdmin: true },
  { key: 'ai', label: 'AI 助手', group: 'integrations', requiresAdmin: true },
  { key: 'audit', label: '审计日志', group: 'governance', requiresAdmin: true },
  { key: 'users', label: '用户管理', group: 'governance', entry: 'identity', requiresAdmin: true, requiresAuthentication: true },
  { key: 'access', label: '访问控制', group: 'governance', entry: 'identity', requiresAdmin: true, requiresAuthentication: true }
];

export function managementSectionLabel(section: ManagementSection): string {
  return MANAGEMENT_SECTIONS.find((item) => item.key === section)?.label ?? '管理';
}

/**
 * 管理员才能用的功能，当前这个人能不能用。
 *
 * <p>认证关闭（桌面版，或显式 APP_AUTH_MODE=DISABLED 的 Web 部署）时没有「角色」这回事，
 * 后端那条过滤链对所有请求放行，前端也必须一视同仁 —— 只认 {@code role === 'ADMIN'} 的话，
 * 认证关闭的装机上这类入口永远不出现（连接归档的「导出 / 导入」就是这样在桌面版上消失的）。</p>
 */
export function canUseAdminFeatures(isAdmin: boolean, authenticationEnabled: boolean): boolean {
  return !authenticationEnabled || isAdmin;
}

export function isManagementSectionVisible(section: ManagementSection, isAdmin = true, authenticationEnabled = true): boolean {
  const meta = MANAGEMENT_SECTIONS.find((item) => item.key === section);
  return Boolean(meta)
    && (!meta?.requiresAuthentication || authenticationEnabled)
    && (!meta?.requiresAdmin || canUseAdminFeatures(isAdmin, authenticationEnabled));
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
export type ManagementNavItem = {
  /** 合并入口用入口 key，单独一项用分区 key。 */
  key: string;
  label: string;
  /** 这一项下可见的分区；多于一个时抽屉顶部出现切换标签。 */
  sections: ManagementSectionMeta[];
};

export function managementNavigation(
  isAdmin = true,
  authenticationEnabled = true
): Array<{ key: ManagementGroup; label: string; sections: ManagementSectionMeta[]; items: ManagementNavItem[] }> {
  return MANAGEMENT_GROUPS
    .map((group) => {
      const sections = MANAGEMENT_SECTIONS.filter((section) => section.group === group.key
        && isManagementSectionVisible(section.key, isAdmin, authenticationEnabled));
      return { ...group, sections, items: navItems(sections) };
    })
    .filter((group) => group.sections.length > 0);
}

function navItems(sections: ManagementSectionMeta[]): ManagementNavItem[] {
  const items: ManagementNavItem[] = [];
  for (const section of sections) {
    const last = items[items.length - 1];
    if (section.entry && last?.key === section.entry) {
      last.sections.push(section);
    } else {
      // 入口里只剩一个可见分区时（例如认证关闭），就用分区自己的名字，不挂一个空壳标题。
      items.push({ key: section.entry ?? section.key, label: section.entry ? MANAGEMENT_ENTRY_LABELS[section.entry] : section.label, sections: [section] });
    }
  }
  return items.map((item) => item.sections.length === 1 ? { ...item, label: item.sections[0].label } : item);
}

/** 当前分区所在的导航项（含同一入口里其它可见分区），抽屉据此决定要不要画切换标签。 */
export function managementNavItemOf(section: ManagementSection, isAdmin = true, authenticationEnabled = true): ManagementNavItem | undefined {
  return managementNavigation(isAdmin, authenticationEnabled)
    .flatMap((group) => group.items)
    .find((item) => item.sections.some((candidate) => candidate.key === section));
}
