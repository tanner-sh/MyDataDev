/**
 * 表数据界面的批量操作：勾选多行之后一起删除、一起改一列、复制成新增行、整批复制。
 *
 * <p>全部只改「待提交变更」这份本地状态，不碰数据库 —— 与逐格编辑同一条路：照样进撤销栈、
 * 照样在预览里看到语句、照样要点「提交」，生产确认与审计都不会因为是批量就被绕过。</p>
 *
 * <p>判定放在这里（有测试），组件只负责勾选和弹窗。</p>
 */
import { serializeCopiedRows } from './queryResultExport';
import type { ResultCopyFormat, TableColumn, TableRow } from './types';

/** 当前还在表格里的勾选：翻页、刷新、移除新增行之后，旧的 id 要筛掉，否则「已选 N 行」会算错。 */
export function liveSelection(rows: TableRow[], selected: readonly string[]): string[] {
  if (selected.length === 0) return [];
  const ids = new Set(rows.map((row) => row.id));
  return selected.filter((id) => ids.has(id));
}

export type BatchSelectionSummary = {
  total: number;
  /** 已标记删除的行数（不含新增行，新增行删除即移除）。 */
  deleted: number;
  inserted: number;
};

export function summarizeSelection(rows: TableRow[], selected: readonly string[]): BatchSelectionSummary {
  const ids = new Set(selected);
  let total = 0;
  let deleted = 0;
  let inserted = 0;
  for (const row of rows) {
    if (!ids.has(row.id)) continue;
    total += 1;
    if (row.deleted) deleted += 1;
    if (row.inserted) inserted += 1;
  }
  return { total, deleted, inserted };
}

/**
 * 批量标记删除 / 撤销删除。
 *
 * <p>与逐行的删除按钮不同，这里不是「切换」：勾了五行、其中两行已经标过删除，点「删除」的意思是
 * 五行都删，而不是两行恢复、三行删除。新增行没有库里的对应行，删除就是直接移除。</p>
 */
export function markRowsDeleted(rows: TableRow[], selected: readonly string[], deleted: boolean): TableRow[] {
  const ids = new Set(selected);
  return rows.flatMap((row) => {
    if (!ids.has(row.id)) return [row];
    if (row.inserted) return deleted ? [] : [row];
    if (Boolean(row.deleted) === deleted) return [row];
    return [{ ...row, deleted }];
  });
}

export type BatchEditContext = {
  /** 表有可用的行定位（主键或全非空唯一索引）；没有时库里已有的行不能改，只有新增行能改。 */
  editable: boolean;
};

/**
 * 一列能不能批量赋值：截断过的列（这一页有超长值）与逐格编辑一样不让改；行定位字段（主键）也不让 ——
 * 把几行的主键设成同一个值必然撞约束，而单独改一行主键仍然可以在格子里改。
 */
export function canBatchEditColumn(column: TableColumn, keyColumns: readonly string[] = []): boolean {
  return column.editable !== false && !column.truncated && !keyColumns.includes(column.name);
}

/**
 * 把勾选行的某一列设成同一个值。value 为 null 即设为 NULL。
 *
 * <p>已标记删除的行跳过（改一行要删的数据没有意义，还会让预览里出现一条注定作废的 UPDATE）；
 * 表没有行定位时只改新增行。返回改了几行，调用方据此提示「跳过了 N 行」。</p>
 */
export function setColumnForRows(
  rows: TableRow[],
  selected: readonly string[],
  column: string,
  value: unknown,
  context: BatchEditContext
): { rows: TableRow[]; changed: number; skipped: number } {
  const ids = new Set(selected);
  let changed = 0;
  let skipped = 0;
  const next = rows.map((row) => {
    if (!ids.has(row.id)) return row;
    if (row.deleted || (!row.inserted && !context.editable)) {
      skipped += 1;
      return row;
    }
    changed += 1;
    if (row.inserted) {
      const touched = new Set(row.touchedColumns || []);
      touched.add(column);
      return { ...row, values: { ...row.values, [column]: value }, touchedColumns: [...touched] };
    }
    return { ...row, values: { ...row.values, [column]: value } };
  });
  return { rows: changed > 0 ? next : rows, changed, skipped };
}

/**
 * 把勾选行复制成新增行（「照着这几行再造几行」）。
 *
 * <p>行定位字段（主键）刻意不复制、保持 DEFAULT 态：原样复制的话每一行都必然撞主键，
 * 提交时整批失败；留空则由数据库默认值 / 序列 / 触发器给值，或者由用户在提交前填上。
 * 新行插在原来那几行的最前面，和「新增行」按钮的位置一致，一眼能看到。</p>
 */
export function duplicateRowsAsInserts(
  rows: TableRow[],
  selected: readonly string[],
  columns: TableColumn[],
  keyColumns: readonly string[],
  idFactory: (index: number) => string
): { rows: TableRow[]; added: TableRow[] } {
  const ids = new Set(selected);
  const skip = new Set(keyColumns);
  const copied = columns.filter((column) => !skip.has(column.name) && !column.truncated).map((column) => column.name);
  const added: TableRow[] = [];
  for (const row of rows) {
    if (!ids.has(row.id) || row.deleted) continue;
    const source = row.inserted ? copied.filter((name) => row.touchedColumns?.includes(name)) : copied;
    const values: Record<string, unknown> = {};
    for (const name of source) values[name] = row.values[name];
    added.push({ id: idFactory(added.length), values, touchedColumns: source, inserted: true });
  }
  return { rows: added.length ? [...added, ...rows] : rows, added };
}

/**
 * 勾选行的复制文本，按表格里的顺序。
 *
 * <p>管道格式带表头（多行粘贴到表格软件里时，没有表头就分不清哪列是哪列）；INSERT 格式里
 * 新增行只写填过的列（其余交给数据库默认值），所以逐行生成而不是拼成一条多值 INSERT。</p>
 */
export function selectedRowsCopyText(
  format: ResultCopyFormat,
  columns: TableColumn[],
  rows: TableRow[],
  selected: readonly string[],
  options: { dbType?: string; targetTableParts?: string[] } = {}
): string {
  const ids = new Set(selected);
  const picked = rows.filter((row) => ids.has(row.id));
  if (picked.length === 0) throw new Error('没有选中任何行');
  if (format === 'pipe') {
    const header = columns.map((column) => column.name).join('|');
    const body = serializeCopiedRows('pipe', columns.map((column) => ({ key: column.name, label: column.name, typeName: column.typeName })),
      picked.map((row) => columns.map((column) => row.values[column.name])));
    return `${header}\n${body}`;
  }
  const statements = picked.flatMap((row) => {
    const included = row.inserted ? columns.filter((column) => row.touchedColumns?.includes(column.name)) : columns;
    if (included.length === 0) return [];
    return [serializeCopiedRows('sql', included.map((column) => ({ key: column.name, label: column.name, typeName: column.typeName })),
      [included.map((column) => row.values[column.name])], options).trimEnd()];
  });
  if (statements.length === 0) throw new Error('选中的都是还没填写任何列的新增行，没有可复制的 INSERT 语句');
  return statements.join('\n');
}
