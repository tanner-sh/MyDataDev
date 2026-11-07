/**
 * 连接配置归档的前端逻辑。
 *
 * <p>只做判断和文案，不碰网络与 DOM —— 归档文件里装着数据库密码，
 * 「这是不是一个归档文件」「口令够不够强」这类判断错了代价很大，值得单独测。</p>
 */

/** 与后端 ConfigArchiveCrypto.FORMAT 一致；改动要两边同步。 */
export const ARCHIVE_FORMAT = 'mydatadev-config-archive';
export const ARCHIVE_FILE_PREFIX = 'mydatadev-connections';
/** 与后端 MIN_PASSPHRASE_LENGTH 一致。 */
export const MIN_ARCHIVE_PASSPHRASE_LENGTH = 12;

export type ArchiveEnvelope = {
  format: string;
  version: number;
  kdf: string;
  iterations: number;
  salt: string;
  cipher: string;
  iv: string;
  payload: string;
};

/** 与后端 ConfigArchiveDtos.ArchiveMcpImportResult 一致。 */
export type ArchiveMcpImportResult = {
  settingsApplied: boolean;
  settingsNote: string;
  importedAgents: string[];
  /** 「agentId：原因」。 */
  skippedAgents: string[];
  /** 「agentId → 连接名：原因」。 */
  droppedGrants: string[];
};

export type ArchiveImportResult = {
  imported: number;
  skipped: number;
  renamed: number;
  importedNames: string[];
  skippedNames: string[];
  /** 没有导入 MCP 配置（归档里没有，或导入时取消勾选）时为空。 */
  mcp?: ArchiveMcpImportResult | null;
};

/**
 * 粗筛：这个文件看起来像不像本程序的归档。
 *
 * <p>只看信封的形状，不试图验证内容 —— 真正的校验在后端由 GCM 完成。这里的作用是让
 * 「选错了文件」立刻得到一句人话，而不是把口令连同一个随机 JSON 一起发出去。</p>
 */
export function isConfigArchive(value: unknown): value is ArchiveEnvelope {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<ArchiveEnvelope>;
  return candidate.format === ARCHIVE_FORMAT
    && typeof candidate.version === 'number'
    && typeof candidate.payload === 'string'
    && candidate.payload.length > 0
    && typeof candidate.salt === 'string'
    && typeof candidate.iv === 'string';
}

/** 导出口令的校验；返回 null 表示通过。 */
export function archivePassphraseError(passphrase: string, confirmation: string): string | null {
  if (passphrase.length < MIN_ARCHIVE_PASSPHRASE_LENGTH) {
    return `口令至少需要 ${MIN_ARCHIVE_PASSPHRASE_LENGTH} 位`;
  }
  if (passphrase !== confirmation) return '两次输入的口令不一致';
  return null;
}

/**
 * 导入结果的说明。
 *
 * <p>跳过的连接要点名：用户以为「导入了 10 条」，实际只进了 3 条、7 条因重名被跳过，
 * 这种差异不说清楚，他会以为配置已经齐了。</p>
 */
export function archiveImportSummary(result: ArchiveImportResult): string {
  const parts = [`导入 ${result.imported} 条`];
  if (result.renamed > 0) parts.push(`其中 ${result.renamed} 条因重名已改名`);
  if (result.skipped > 0) {
    const names = result.skippedNames.slice(0, 5).join('、');
    const more = result.skippedNames.length > 5 ? ` 等 ${result.skippedNames.length} 条` : '';
    parts.push(`跳过 ${result.skipped} 条重名连接：${names}${more}`);
  }
  return parts.join('；') + '。';
}

/**
 * MCP 部分导入结果的逐条说明。
 *
 * <p>Agent 被跳过、授权被丢掉都要带原因点名：用户以为 Agent 的权限原样搬过来了，实际有一条连接因重名
 * 没进来、授权也就没挂上，不说清楚的话他会在 AI 客户端里撞上「无权访问」才发现。</p>
 *
 * <p>导入了 Agent 时最后一条告诉用户 AI 客户端要改什么：Key 沿用，只有地址变了。
 * {@code mcpEndpoint} 由调用方传入本机的 MCP 地址（通常是 {@code location.origin + '/mcp'}）。</p>
 */
export function archiveMcpImportNotes(mcp: ArchiveMcpImportResult, mcpEndpoint: string): string[] {
  const notes: string[] = [];
  notes.push(mcp.importedAgents.length > 0
    ? `MCP Agent 导入 ${mcp.importedAgents.length} 个：${mcp.importedAgents.join('、')}`
    : 'MCP Agent 没有导入任何一个');
  for (const skipped of mcp.skippedAgents) notes.push(`跳过 Agent ${skipped}`);
  for (const dropped of mcp.droppedGrants) notes.push(`未挂上的授权 ${dropped}`);
  notes.push(mcp.settingsNote);
  if (mcp.importedAgents.length > 0) {
    notes.push(`AI 客户端里的 Key 不用换，只需把 MCP 地址改成 ${mcpEndpoint}`);
  }
  return notes;
}
