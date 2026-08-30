/**
 * 审计日志的查询与展示逻辑。
 *
 * 审计表此前只写不读：十多个服务往里写，却没有任何接口和界面能查。而 /api 本身没有
 * 用户认证（安全边界由外层反向代理承担），「谁在哪条连接上做了什么」是这张表存在的
 * 唯一意义。
 */

export const AUDIT_PAGE_SIZE = 50;
export const AUDIT_SEARCH_DEBOUNCE_MS = 300;

export type AuditCategory = 'sql' | 'data' | 'schema' | 'connection' | 'backup' | 'restore' | 'mcp' | 'storage' | 'file' | 'security';

export type AuditQuery = {
  actor?: string;
  action?: string;
  connectionId?: number;
  keyword: string;
  from?: string;
  to?: string;
  page: number;
};

export const INITIAL_AUDIT_QUERY: AuditQuery = { keyword: '', page: 0 };

type ActionMeta = { label: string; category: AuditCategory; dangerous?: boolean };

/**
 * 动作码到中文的映射。
 *
 * 未收录的码会退回原样显示而不是隐藏 —— 新增审计动作时忘了同步这里，最坏情况是看到
 * 一个英文常量，而不是看不到这条记录。
 */
const ACTIONS: Readonly<Record<string, ActionMeta>> = {
  SQL_EXECUTE: { label: '执行 SQL', category: 'sql' },
  SQL_EXECUTE_SCRIPT: { label: '执行 SQL 脚本', category: 'sql' },
  SQL_QUERY_PAGE: { label: '查询结果翻页', category: 'sql' },
  SQL_EXPLAIN: { label: '查看执行计划', category: 'sql' },
  SQL_EXPORT: { label: '导出查询结果', category: 'sql' },
  SQL_UNSCOPED_MUTATION_CONFIRMED: { label: '确认执行无 WHERE 的写操作', category: 'sql', dangerous: true },
  SQL_FILE_UPLOAD: { label: '上传 SQL 文件', category: 'file' },
  SQL_FILE_START: { label: '开始执行 SQL 文件', category: 'file', dangerous: true },
  SQL_FILE_CANCEL: { label: '取消 SQL 文件执行', category: 'file' },
  SQL_FILE_SUCCESS: { label: 'SQL 文件执行成功', category: 'file' },
  SQL_FILE_FAILED: { label: 'SQL 文件执行失败', category: 'file', dangerous: true },
  DATA_IMPORT_UPLOAD: { label: '上传 CSV 导入任务', category: 'data' },
  SQL_TRANSACTION_BEGIN: { label: '开启手动事务', category: 'sql' },
  SQL_TRANSACTION_COMMIT: { label: '提交手动事务', category: 'sql', dangerous: true },
  SQL_TRANSACTION_ROLLBACK: { label: '回滚手动事务', category: 'sql' },
  SQL_TRANSACTION_TIMEOUT: { label: '手动事务空闲超时自动回滚', category: 'sql' },
  SQL_SNIPPET_CREATE: { label: '新建 SQL 片段', category: 'sql' },
  SQL_SNIPPET_UPDATE: { label: '修改 SQL 片段', category: 'sql' },
  SQL_SNIPPET_DELETE: { label: '删除 SQL 片段', category: 'sql' },
  SESSION_KILL: { label: '终止数据库会话', category: 'connection', dangerous: true },
  RESTORE_SUCCESS: { label: '恢复任务完成', category: 'restore' },
  MCP_TOOL_CALL: { label: 'MCP 工具调用', category: 'mcp' },
  DATA_COMMIT: { label: '提交表数据变更', category: 'data', dangerous: true },
  TABLE_DESIGN_EXECUTE: { label: '执行表结构变更', category: 'schema', dangerous: true },
  TABLE_CREATE: { label: '新建表', category: 'schema' },
  TABLE_RENAME: { label: '重命名表', category: 'schema', dangerous: true },
  TABLE_DROP: { label: '删除表', category: 'schema', dangerous: true },
  SCHEMA_DIFF: { label: '对比 Schema 结构', category: 'schema' },
  OBJECT_INVOKE: { label: '调用数据库对象', category: 'schema' },
  OBJECT_INVOKE_FAILED: { label: '调用数据库对象失败', category: 'schema' },
  CONNECTION_CREATE: { label: '新建连接', category: 'connection' },
  CONNECTION_UPDATE: { label: '修改连接', category: 'connection' },
  CONNECTION_DELETE: { label: '删除连接', category: 'connection', dangerous: true },
  BACKUP_TASK_CREATE: { label: '新建备份任务', category: 'backup' },
  BACKUP_TASK_UPDATE: { label: '修改备份任务', category: 'backup' },
  BACKUP_TASK_DELETE: { label: '删除备份任务', category: 'backup', dangerous: true },
  BACKUP_TASK_RUN: { label: '执行备份', category: 'backup' },
  BACKUP_TASK_CANCEL: { label: '取消备份', category: 'backup' },
  BACKUP_TASK_CANCELLED: { label: '备份已取消', category: 'backup' },
  BACKUP_HISTORY_DELETE: { label: '删除备份历史', category: 'backup', dangerous: true },
  BACKUP_UPLOAD_FAILED: { label: '备份上传失败', category: 'backup' },
  BACKUP_UPLOAD_RETRY: { label: '重试备份上传', category: 'backup' },
  BACKUP_UPLOAD_RETRY_SUCCESS: { label: '备份上传重试成功', category: 'backup' },
  BACKUP_UPLOAD_RETRY_FAILED: { label: '备份上传重试失败', category: 'backup' },
  RESTORE_START: { label: '开始恢复', category: 'restore', dangerous: true },
  RESTORE_CANCEL: { label: '取消恢复', category: 'restore' },
  RESTORE_UPLOAD_ACCESS_DENIED: { label: '恢复上传文件访问被拒绝', category: 'security', dangerous: true },
  MCP_SQL_QUERY: { label: 'MCP 查询', category: 'mcp' },
  MCP_SQL_EXPLAIN: { label: 'MCP 执行计划', category: 'mcp' },
  MCP_AGENT_CREATE: { label: '新建 MCP Agent', category: 'mcp' },
  MCP_AGENT_UPDATE: { label: '修改 MCP Agent', category: 'mcp' },
  MCP_AGENT_DELETE: { label: '删除 MCP Agent', category: 'mcp', dangerous: true },
  MCP_AGENT_ROTATE_KEY: { label: '轮换 MCP 密钥', category: 'mcp' },
  MCP_CONFIG_UPDATE: { label: '修改 MCP 配置', category: 'mcp' },
  STORAGE_PROFILE_CREATE: { label: '新建存储配置', category: 'storage' },
  STORAGE_PROFILE_UPDATE: { label: '修改存储配置', category: 'storage' },
  STORAGE_PROFILE_DELETE: { label: '删除存储配置', category: 'storage', dangerous: true },
  STORAGE_PROFILE_TEST: { label: '测试存储配置', category: 'storage' },
  USER_CREATE: { label: '新建用户', category: 'security' },
  USER_UPDATE: { label: '修改用户', category: 'security' },
  USER_DELETE: { label: '删除用户', category: 'security', dangerous: true },
  USER_ENABLE: { label: '启用用户', category: 'security' },
  USER_DISABLE: { label: '停用用户', category: 'security', dangerous: true },
  USER_PASSWORD_RESET: { label: '重置用户密码', category: 'security', dangerous: true },
  USER_ROLE_CHANGE: { label: '修改用户角色', category: 'security', dangerous: true },
  USER_GROUP_CREATE: { label: '新建用户组', category: 'security' },
  USER_GROUP_UPDATE: { label: '修改用户组', category: 'security' },
  USER_GROUP_DELETE: { label: '删除用户组', category: 'security', dangerous: true },
  CONNECTION_ACCESS_UPDATE: { label: '修改连接权限', category: 'security', dangerous: true },
  CONNECTION_ACCESS_DENIED: { label: '连接访问被拒绝', category: 'security', dangerous: true },
  AUTH_LOGIN: { label: '用户登录', category: 'security' },
  AUTH_LOGIN_FAILED: { label: '登录失败', category: 'security', dangerous: true },
  AUTH_LOGOUT: { label: '用户退出', category: 'security' },
  AUTHORIZATION_DENIED: { label: '系统权限被拒绝', category: 'security', dangerous: true },
  TABLE_VIEW: { label: '查看表数据', category: 'data' },
  BACKUP_DOWNLOAD: { label: '下载备份', category: 'backup' },
  BACKUP_HISTORY_DOWNLOAD: { label: '下载备份历史', category: 'backup' },
  AUDIT_EXPORT: { label: '导出审计日志', category: 'security' }
};

export function auditActionLabel(action: string): string {
  return ACTIONS[action]?.label || action;
}

export function auditActionCategory(action: string): AuditCategory | undefined {
  return ACTIONS[action]?.category;
}

export function isDangerousAuditAction(action: string): boolean {
  return ACTIONS[action]?.dangerous === true;
}

const CATEGORY_COLORS: Readonly<Record<AuditCategory, string>> = {
  sql: 'blue',
  data: 'volcano',
  schema: 'purple',
  connection: 'geekblue',
  backup: 'cyan',
  restore: 'gold',
  mcp: 'magenta',
  storage: 'green',
  file: 'orange',
  security: 'red'
};

export function auditActionColor(action: string): string | undefined {
  const category = auditActionCategory(action);
  return category ? CATEGORY_COLORS[category] : undefined;
}

/** target 的统一写法是 `connection:<id>`，后面可能还跟着 ` table:xxx`。 */
export function parseAuditConnectionId(target?: string | null): number | undefined {
  if (!target) return undefined;
  const match = /^connection:(\d+)(?:\s|$)/.exec(target);
  if (!match) return undefined;
  const id = Number(match[1]);
  return Number.isSafeInteger(id) && id > 0 ? id : undefined;
}

/** 把 target 渲染成人能读的样子，认不出来的原样返回。 */
export function auditTargetLabel(target: string | null | undefined, connectionName?: string): string {
  if (!target) return '—';
  const connectionId = parseAuditConnectionId(target);
  if (connectionId === undefined) return target;
  const rest = target.slice(`connection:${connectionId}`.length).trim();
  const head = connectionName || `连接 #${connectionId}`;
  // 连接自身的增删改把连接名写进了 subject（连接删掉之后 id 就查不到名字了，那时它是
  // 唯一线索）。但连接还在时前端已经解析出同一个名字，再拼一次就成了「X · X」。
  if (!rest || rest === head) return head;
  return `${head} · ${rest}`;
}

export function auditRequestParams(query: AuditQuery, pageSize = AUDIT_PAGE_SIZE): string {
  const params = new URLSearchParams({
    page: String(Math.max(query.page, 0)),
    pageSize: String(pageSize)
  });
  if (query.actor) params.set('actor', query.actor);
  if (query.action) params.set('action', query.action);
  if (query.connectionId != null) params.set('connectionId', String(query.connectionId));
  const keyword = query.keyword.trim();
  if (keyword) params.set('keyword', keyword);
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  return params.toString();
}

/** 底部说明必须如实反映还有没有更多，不能让分页控件暗示不存在的数据。 */
export function auditPageSummary(loaded: number, page: number, hasMore: boolean): string {
  if (loaded === 0) return page === 0 ? '没有匹配的审计记录' : '这一页没有记录';
  const start = page * AUDIT_PAGE_SIZE + 1;
  const end = page * AUDIT_PAGE_SIZE + loaded;
  return hasMore ? `第 ${start}-${end} 条，还有更早的记录` : `第 ${start}-${end} 条，已是最后一页`;
}

/**
 * 审计链状态标签。
 *
 * <p>单次校验有事件数上限，验不到表尾时返回 complete=false —— 这种情况绝不能显示成
 * 「审计链完整」：只验了最早的一段就报完整，等于把最近可能被改过的记录说成没问题。</p>
 */
export function auditChainTag(chain: {
  valid: boolean;
  checkedEvents: number;
  firstInvalidId?: number;
  complete: boolean;
}): { color: string; label: string; tooltip: string } {
  if (!chain.valid) {
    return { color: 'red', label: '审计链异常', tooltip: `第 ${chain.firstInvalidId} 条附近校验失败` };
  }
  if (!chain.complete) {
    return {
      color: 'gold',
      label: '审计链部分校验',
      tooltip: `已从最早的记录校验 ${chain.checkedEvents} 条，未到达最新记录（单次校验有上限）`
    };
  }
  return { color: 'green', label: '审计链完整', tooltip: `已校验 ${chain.checkedEvents} 条事件` };
}

/**
 * 导出被截断时的提示文案。
 *
 * <p>服务端单次最多导出 10000 条，超出部分直接没了。CSV 里没有任何地方能说明「后面还有」，
 * 不在界面上讲清楚，拿到文件的人会当成完整记录来用。</p>
 */
export function auditExportNotice(rowsHeader: string | null, cappedHeader: string | null): string | null {
  if (cappedHeader !== 'true') return null;
  const rows = Number(rowsHeader);
  const count = Number.isFinite(rows) && rows > 0 ? rows : 0;
  return `导出已达到单次上限，只包含最新的 ${count} 条记录。请缩小时间范围或筛选条件后分批导出。`;
}
