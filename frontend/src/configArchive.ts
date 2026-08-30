/**
 * 连接配置归档的前端逻辑。
 *
 * <p>只做判断和文案，不碰网络与 DOM —— 归档文件里装着数据库密码，
 * 「这是不是一个归档文件」「口令够不够强」这类判断错了代价很大，值得单独测。</p>
 */

/** 与后端 ConfigArchiveCrypto.FORMAT 一致；改动要两边同步。 */
export const ARCHIVE_FORMAT = 'mydatadev-config-archive';
export const ARCHIVE_VERSION = 1;
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

export type ArchiveImportResult = {
  imported: number;
  skipped: number;
  renamed: number;
  importedNames: string[];
  skippedNames: string[];
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
