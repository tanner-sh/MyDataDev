/**
 * 执行计划解读。
 *
 * 执行计划此前只是照原样铺成一张表：MySQL 的 type=ALL、Extra 里的 Using filesort、动辄上百万
 * 的 rows 估算全都混在其它列里，只有已经知道该看哪几列的人才看得懂。这里把「明显有问题」的
 * 信号挑出来，用中文说清楚是什么、为什么值得关心。
 *
 * 只报有把握的信号：宁可漏，也不要把正常的计划标成红色 —— 一旦用户发现警告经常是误报，
 * 之后真正的问题也会被跳过。
 */

/** 预估扫描行数超过这个量级就值得提一句；再小的表全表扫描往往是最优解。 */
export const LARGE_ROW_ESTIMATE = 100_000;

export type ExplainFindingLevel = 'warning' | 'notice';

export type ExplainFinding = {
  level: ExplainFindingLevel;
  /** 归类键：同一类问题合并成一条，只把命中的行号列出来。 */
  code: string;
  title: string;
  detail: string;
  /** 命中的行序号（0 起，对应结果集里的原始顺序）。 */
  rows: number[];
};

/** 单列文本计划的列名：PostgreSQL / Oracle / H2。 */
const TEXT_PLAN_COLUMNS = new Set(['query plan', 'plan_table_output', 'plan']);
/** MySQL / MariaDB 经典 EXPLAIN 的特征列。 */
const TABULAR_PLAN_COLUMNS = ['select_type', 'possible_keys'];
/** SQLite 的 EXPLAIN QUERY PLAN。 */
const SQLITE_PLAN_COLUMNS = ['parent', 'notused', 'detail'];

function lower(values: string[]): string[] {
  return values.map((value) => (value || '').trim().toLowerCase());
}

/**
 * 计划的形态。
 *
 * <p>必须先分清方言再套规则：PostgreSQL 的「Index Scan using idx」里也有 scan 一词，若拿
 * SQLite 的 SCAN 规则去匹配，一条走了索引的计划会被误报成表扫描。</p>
 */
export type ExplainPlanShape = 'mysql' | 'text' | 'sqlite' | 'unknown';

export function explainPlanShape(columns: string[]): ExplainPlanShape {
  const normalized = lower(columns);
  if (SQLITE_PLAN_COLUMNS.every((column) => normalized.includes(column))) return 'sqlite';
  if (TABULAR_PLAN_COLUMNS.every((column) => normalized.includes(column))) return 'mysql';
  if (normalized.length === 1 && TEXT_PLAN_COLUMNS.has(normalized[0])) return 'text';
  return 'unknown';
}

/**
 * 判断一份结果集是不是执行计划。
 *
 * <p>按列结构识别而不是按 SQL 文本：走「生成执行计划」按钮时结果里记的是原始 SELECT，
 * 而用户也可能自己在编辑器里敲 EXPLAIN。列结构对两种情况都成立。</p>
 */
export function isExplainResult(columns: string[]): boolean {
  return explainPlanShape(columns) !== 'unknown';
}

function columnIndex(columns: string[], name: string): number {
  return lower(columns).indexOf(name);
}

function cellText(row: unknown[], index: number): string {
  if (index < 0) return '';
  const value = row[index];
  return value == null ? '' : String(value);
}

function parseCount(value: string): number | null {
  const parsed = Number(value.replace(/,/g, ''));
  return Number.isFinite(parsed) ? parsed : null;
}

function formatCount(value: number): string {
  return value >= 10_000 ? `${Math.round(value / 10_000)} 万` : String(value);
}

type Accumulator = Map<string, ExplainFinding>;

function record(findings: Accumulator, finding: Omit<ExplainFinding, 'rows'>, rowIndex: number) {
  const existing = findings.get(finding.code);
  if (existing) {
    if (!existing.rows.includes(rowIndex)) existing.rows.push(rowIndex);
    return;
  }
  findings.set(finding.code, { ...finding, rows: [rowIndex] });
}

/**
 * 从执行计划里挑出值得关注的信号。
 *
 * <p>同一类问题合并成一条，命中的行号一起给出 —— 一个 5 表关联里三张表全表扫描应该读作
 * 「三处全表扫描」，而不是刷出三条一模一样的警告。</p>
 */
export function explainFindings(columns: string[], rows: unknown[][]): ExplainFinding[] {
  const shape = explainPlanShape(columns);
  if (shape === 'unknown') return [];
  const findings: Accumulator = new Map();
  const typeIndex = columnIndex(columns, 'type');
  const extraIndex = columnIndex(columns, 'extra');
  const keyIndex = columnIndex(columns, 'key');
  const possibleKeysIndex = columnIndex(columns, 'possible_keys');
  const rowsIndex = columnIndex(columns, 'rows');

  rows.forEach((row, rowIndex) => {
    if (shape === 'mysql') {
      const type = cellText(row, typeIndex).trim().toLowerCase();
      const extra = cellText(row, extraIndex).toLowerCase();
      const key = cellText(row, keyIndex).trim();
      const possibleKeys = cellText(row, possibleKeysIndex).trim();
      const estimated = parseCount(cellText(row, rowsIndex));

      if (type === 'all') {
        record(findings, {
          level: 'warning',
          code: 'full-table-scan',
          title: '全表扫描（type=ALL）',
          detail: '这一步没有走任何索引，需要读完整张表。数据量增长后耗时会线性上升，通常给 WHERE 或 JOIN 条件上的列加索引就能消除。'
        }, rowIndex);
      } else if (type === 'index') {
        record(findings, {
          level: 'notice',
          code: 'full-index-scan',
          title: '全索引扫描（type=index）',
          detail: '走了索引但仍要遍历整棵索引树，只比全表扫描省下回表的开销。'
        }, rowIndex);
      }
      if (!key && possibleKeys) {
        record(findings, {
          level: 'warning',
          code: 'unused-index',
          title: '有候选索引却没有使用',
          detail: `possible_keys 里有 ${possibleKeys}，但优化器最终没选任何索引。常见原因是条件列上有函数或隐式类型转换。`
        }, rowIndex);
      }
      if (extra.includes('using filesort')) {
        record(findings, {
          level: 'warning',
          code: 'filesort',
          title: '需要额外排序（Using filesort）',
          detail: 'ORDER BY 的顺序无法直接由索引提供，要把结果取出来再排一遍；结果集大时会落盘。'
        }, rowIndex);
      }
      if (extra.includes('using temporary')) {
        record(findings, {
          level: 'warning',
          code: 'temporary-table',
          title: '需要临时表（Using temporary）',
          detail: 'GROUP BY / DISTINCT / 复杂 ORDER BY 需要先物化成临时表，行数一多就会写到磁盘上。'
        }, rowIndex);
      }
      if (extra.includes('using join buffer')) {
        record(findings, {
          level: 'warning',
          code: 'join-buffer',
          title: '关联没有走索引（Using join buffer）',
          detail: '被驱动表的关联列上没有可用索引，只能靠连接缓冲逐块比较，代价随两表行数相乘增长。'
        }, rowIndex);
      }
      if (estimated != null && estimated >= LARGE_ROW_ESTIMATE) {
        record(findings, {
          level: 'warning',
          code: 'large-row-estimate',
          title: `预估扫描行数偏大（约 ${formatCount(estimated)} 行）`,
          detail: '优化器预计这一步要处理的行数很大。若与实际数据量不符，可能是统计信息过期，可以先更新统计信息再看。'
        }, rowIndex);
      }
      return;
    }

    const text = row.map((value) => (value == null ? '' : String(value))).join(' ');
    const normalized = text.toLowerCase();

    if (shape === 'sqlite') {
      // SQLite 用 SCAN / SEARCH 区分「逐行遍历」和「走了索引」，只有 SCAN 值得提。
      if (/\bscan\b/.test(normalized)) {
        record(findings, {
          level: 'notice',
          code: 'sqlite-scan',
          title: '表扫描（SCAN）',
          detail: 'SQLite 的 SCAN 表示逐行遍历；SEARCH 才表示走了索引。'
        }, rowIndex);
      }
      return;
    }

    // 单列文本计划：PostgreSQL / Oracle / H2。
    // H2 把选中的访问路径写在注释里：走索引是 /* PUBLIC.IDX_NOTE: ... */，
    // 全表扫描是 /* PUBLIC.ORDERS.tableScan */。
    if (normalized.includes('.tablescan')) {
      record(findings, {
        level: 'warning',
        code: 'h2-table-scan',
        title: '全表扫描（tableScan）',
        detail: '这一步没有走任何索引，需要读完整张表。给 WHERE 或 ORDER BY 用到的列加索引通常就能消除。'
      }, rowIndex);
    }
    if (normalized.includes('seq scan on')) {
      record(findings, {
        level: 'warning',
        code: 'seq-scan',
        title: '顺序扫描（Seq Scan）',
        detail: '这一步读完整张表。小表上顺序扫描往往就是最优解，但大表上通常意味着缺少可用索引。'
      }, rowIndex);
    }
    if (normalized.includes('table access full')) {
      record(findings, {
        level: 'warning',
        code: 'oracle-full-scan',
        title: '全表扫描（TABLE ACCESS FULL）',
        detail: '这一步读完整张表。确认过滤条件上的列是否有索引，以及索引是否因函数或类型转换失效。'
      }, rowIndex);
    }
    if (normalized.includes('external merge') || normalized.includes('external sort')) {
      record(findings, {
        level: 'warning',
        code: 'external-sort',
        title: '排序落盘（external merge）',
        detail: '排序所需内存超过了 work_mem，中间结果被写到磁盘上，是执行计划里最贵的一类步骤。'
      }, rowIndex);
    }
    const estimated = /\brows=(\d+)/.exec(normalized);
    if (estimated) {
      const value = Number(estimated[1]);
      if (value >= LARGE_ROW_ESTIMATE) {
        record(findings, {
          level: 'notice',
          code: 'large-row-estimate',
          title: `预估行数偏大（约 ${formatCount(value)} 行）`,
          detail: '优化器预计这一步要处理的行数很大。若与实际数据量不符，可能是统计信息过期。'
        }, rowIndex);
      }
    }
  });

  // 警告排在提示前面；同级按首次命中的行序，保持与计划本身一致的阅读顺序。
  return [...findings.values()].sort((left, right) => {
    if (left.level !== right.level) return left.level === 'warning' ? -1 : 1;
    return left.rows[0] - right.rows[0];
  });
}

/** 行号 → 严重程度，用于在结果网格里标出问题行；同一行取最高级别。 */
export function explainRowLevels(findings: ExplainFinding[]): Map<number, ExplainFindingLevel> {
  const levels = new Map<number, ExplainFindingLevel>();
  for (const finding of findings) {
    for (const rowIndex of finding.rows) {
      if (finding.level === 'warning' || !levels.has(rowIndex)) levels.set(rowIndex, finding.level);
    }
  }
  return levels;
}

export function explainFindingsSummary(findings: ExplainFinding[]): string {
  const warnings = findings.filter((finding) => finding.level === 'warning').length;
  const notices = findings.length - warnings;
  if (warnings === 0 && notices === 0) return '执行计划中没有发现明显的性能问题';
  const parts: string[] = [];
  if (warnings > 0) parts.push(`${warnings} 项需要关注`);
  if (notices > 0) parts.push(`${notices} 项提示`);
  return `执行计划解读：${parts.join('，')}`;
}

/** 计划文本化时单个单元格的字符上限：计划里偶尔会有很长的过滤条件。 */
const MAX_PLAN_CELL_CHARS = 300;
/** 计划文本化的总字符上限。 */
const MAX_PLAN_CHARS = 12_000;

/**
 * 把执行计划渲染成发给模型的文本。
 *
 * 用制表符分隔的表格而不是 JSON：计划本来就是表格，JSON 会把列名在每一行重复一遍，
 * 同样的信息多花一倍 token。
 */
export function explainPlanText(columns: string[], rows: unknown[][]): string {
  if (columns.length === 0) return '';
  const cell = (value: unknown) => {
    const text = value == null ? 'NULL' : String(value).replace(/\s+/g, ' ').trim();
    return text.length > MAX_PLAN_CELL_CHARS ? `${text.slice(0, MAX_PLAN_CELL_CHARS)}…` : text;
  };
  const lines = [columns.join('\t'), ...rows.map((row) => row.map(cell).join('\t'))];
  const text = lines.join('\n');
  return text.length > MAX_PLAN_CHARS ? `${text.slice(0, MAX_PLAN_CHARS)}\n…（计划过长已截断）` : text;
}

/**
 * 把确定性规则的结论渲染成文本，一并发给模型。
 *
 * 模型不该重新判断一遍「这是不是全表扫描」—— 那是规则已经确定的事实。发过去是为了让它
 * 在这些结论之上解释与给建议。
 */
export function explainFindingsText(findings: ExplainFinding[]): string {
  if (findings.length === 0) return '';
  return findings
    .map((finding) => {
      const rows = finding.rows.map((index) => index + 1).join('、');
      const level = finding.level === 'warning' ? '需要关注' : '提示';
      return `[${level}] ${finding.title}（第 ${rows} 行）：${finding.detail}`;
    })
    .join('\n');
}
