/**
 * 导入路径选择。
 *
 * <p>浏览器里解析导入文件有硬性上限：整份文件要读进内存，行还要变成待提交变更留在表格里，
 * 所以 10 MB / 1000 行是合理的天花板 —— 在这个量级内，先看到数据再提交是更好的体验。</p>
 *
 * <p>超过这个量级的 CSV 改走后端的大文件管线：服务端流式转成批量 INSERT，复用 SQL 文件执行
 * 的进度、取消与每连接并发闸门。这里只负责判断走哪条路，以及把原因讲清楚。</p>
 *
 * <p>Excel 一律走后台：浏览器这一侧只会写 xlsx（导出），不会读 —— 解析在服务端，那里还要
 * 处理日期这类只写在样式里的信息。</p>
 */

export const INLINE_IMPORT_MAX_BYTES = 10 * 1024 * 1024;

export type ImportFileInfo = { name: string; size: number };

/** 后台导入的两种源格式，对应两个上传端点。 */
export type BackgroundImportFormat = 'csv' | 'xlsx';

/**
 * 目标表里已经有同主键的行时怎么办。
 *
 * <p>默认仍是 INSERT（撞了就整批失败）—— 它最安全，也和以前的行为一致。另外两档要用户明确
 * 选择：SKIP 会悄悄少写一部分行，UPSERT 会覆盖库里已有的值，两件事都不该默默发生。</p>
 */
export type ImportConflictMode = 'INSERT' | 'SKIP' | 'UPSERT';

export const IMPORT_CONFLICT_OPTIONS: ReadonlyArray<{ value: ImportConflictMode; label: string; hint: string }> = [
  { value: 'INSERT', label: '直接插入', hint: '遇到重复主键就报错中止，不写入任何一行（默认）' },
  { value: 'SKIP', label: '跳过重复行', hint: '已存在的行原样保留，只写入新行' },
  { value: 'UPSERT', label: '更新已存在的行', hint: '按主键覆盖已有行的其余字段' }
];

export type ImportRoute =
  /** 浏览器内解析，落到待提交变更里，用户可以逐行核对后再提交。 */
  | { kind: 'inline' }
  /** 交给后端转成 INSERT 脚本后台执行。 */
  | { kind: 'background'; reason: string; format: BackgroundImportFormat }
  | { kind: 'unsupported'; message: string };

export function importFileExtension(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase();
}

export function formatImportSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

/**
 * 决定一份文件走哪条导入路径。
 *
 * @param pendingChanges 当前待提交变更数：已经堆了很多修改时，再往里塞导入行只会撞上提交上限，
 *   不如直接走后台。
 */
export function importRoute(file: ImportFileInfo, pendingChanges: number, maxChanges: number): ImportRoute {
  const extension = importFileExtension(file.name);
  if (extension !== 'csv' && extension !== 'xlsx' && extension !== 'json' && extension !== 'sql') {
    return { kind: 'unsupported', message: '仅支持导入 CSV、Excel、JSON、SQL 文件。' };
  }
  const tooLarge = file.size > INLINE_IMPORT_MAX_BYTES;
  // Excel 不在浏览器里解析：日期这类信息只写在样式里，那部分逻辑在服务端。
  if (extension === 'xlsx') {
    return { kind: 'background', reason: 'Excel 文件由服务端解析', format: 'xlsx' };
  }
  if (extension === 'csv') {
    if (tooLarge) {
      return { kind: 'background', format: 'csv', reason: `文件 ${formatImportSize(file.size)}，超过浏览器内解析的 ${formatImportSize(INLINE_IMPORT_MAX_BYTES)} 上限` };
    }
    if (pendingChanges >= maxChanges) {
      return { kind: 'background', format: 'csv', reason: `当前已有 ${pendingChanges} 项待提交变更，无法再放入导入行` };
    }
    return { kind: 'inline' };
  }
  if (tooLarge) {
    return {
      kind: 'unsupported',
      message: extension === 'sql'
        ? `SQL 文件 ${formatImportSize(file.size)} 过大，请改用「SQL 文件执行」入口，它支持后台执行与取消。`
        : `JSON 文件 ${formatImportSize(file.size)} 过大，请转存为 CSV 后重试，CSV 支持后台导入。`
    };
  }
  return { kind: 'inline' };
}

/**
 * 浏览器内解析完之后才发现行数超限时，还能往哪走。
 *
 * <p>行数在解析前是不知道的，而 {@link importRoute} 只看得到文件大小 —— 一份 100 KB、1001 行
 * 的 CSV 会先被判成 inline，解析完再撞上待提交变更上限。以前这里只是报错，用户手上那份文件
 * 就没有任何入口可用了；CSV 明明还有后台这条路，直接改走后台即可。</p>
 */
export function oversizedRowsRoute(
  file: ImportFileInfo,
  rows: number,
  maxChanges: number
): Extract<ImportRoute, { kind: 'background' | 'unsupported' }> {
  if (importFileExtension(file.name) === 'csv') {
    return { kind: 'background', format: 'csv', reason: `解析出 ${rows} 行，超过浏览器内一次可提交的 ${maxChanges} 项变更上限` };
  }
  return {
    kind: 'unsupported',
    message: `单次最多提交 ${maxChanges} 项变更；请先提交现有修改，或转存为 CSV 后走后台导入。`
  };
}

/** 后台导入的上传地址；schemaName 为空表示用连接默认命名空间。 */
export function backgroundImportPath(params: {
  connectionId: number;
  schemaName?: string;
  tableName: string;
  fileName: string;
  format?: BackgroundImportFormat;
  conflictMode?: ImportConflictMode;
}): string {
  const query = new URLSearchParams({
    connectionId: String(params.connectionId),
    tableName: params.tableName,
    fileName: params.fileName
  });
  if (params.schemaName) query.set('schemaName', params.schemaName);
  if (params.conflictMode && params.conflictMode !== 'INSERT') query.set('conflictMode', params.conflictMode);
  return `/sql-file-executions/${params.format === 'xlsx' ? 'xlsx' : 'csv'}-imports?${query.toString()}`;
}

export function backgroundImportPrompt(target: string, route: Extract<ImportRoute, { kind: 'background' }>): string {
  return `${route.reason}，将转为后台导入任务写入 ${target}。转换完成后可以在「SQL 文件执行」里查看进度、随时取消。`;
}
