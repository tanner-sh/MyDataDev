/**
 * 表数据工作区（`EditableTable`）的单元格右键菜单。
 *
 * <p>查询结果表格早就有右键菜单了，双击表名打开的表数据界面却没有 —— 用户反馈的正是这里：
 * 想复制一格、复制一行，只能双击进编辑框再全选。菜单项判定放在这里（有测试），渲染交给组件。</p>
 *
 * <p>「设为 NULL / 设为空字符串」沿用 {@link cellValueMenuItems} 的那一套，不另写一份：两张表
 * 在同一个库上对同一个值必须给出同样的提示（Oracle 把空串存成 NULL）。整行复制用的是结果表格
 * 「复制」按钮的同一套序列化（管道分隔 / INSERT），粘贴出来的东西两边一样。</p>
 */

import type { CellDisplayKind } from './cellDraft';
import { cellValueMenuItems } from './cellValueMenu';
import { serializeCopiedRows } from './queryResultExport';
import type { TableColumn } from './types';

export type TableCellMenuAction = 'copy' | 'copy-row' | 'copy-row-insert' | 'copy-column-name' | 'set-null' | 'set-empty' | 'toggle-delete';

/**
 * 这一行能做的删除类操作。每行一个删除图标的「操作」列去掉之后，单行删除走这里，多行走勾选后的批量栏。
 * 不能动这一行（只读连接、加载中、无行定位的存量行）时为 undefined。
 */
export type TableRowDeleteAction = 'delete' | 'undo-delete' | 'remove-inserted';

export type TableCellMenuItem =
  | { type: 'divider' }
  | { type?: undefined; action: TableCellMenuAction; label: string; hint?: string; disabled?: boolean };

export type TableCellMenuContext = {
  /** 这一格此刻能不能改：只读连接、无行定位、已标记删除、截断列都不能。 */
  editable: boolean;
  /** 这一格是不是新增行里还没填过的 DEFAULT 态。 */
  isDefault: boolean;
  displayKind: CellDisplayKind;
  dbType?: string;
  /** 能不能生成 INSERT：缺表名时为 false。 */
  canInsert: boolean;
  rowAction?: TableRowDeleteAction;
};

const ROW_ACTION_LABELS: Record<TableRowDeleteAction, string> = {
  delete: '标记删除此行',
  'undo-delete': '撤销删除此行',
  'remove-inserted': '移除这条新增行'
};

export function tableCellMenuItems(context: TableCellMenuContext): TableCellMenuItem[] {
  const items: TableCellMenuItem[] = [
    // DEFAULT 态没有值可复制 —— 复制出一个空串会让人以为那一格是空字符串。
    { action: 'copy', label: '复制单元格值', disabled: context.isDefault },
    { action: 'copy-row', label: '复制整行' },
    {
      action: 'copy-row-insert',
      label: '复制整行为 INSERT 语句',
      disabled: !context.canInsert
    },
    { action: 'copy-column-name', label: '复制列名' }
  ];
  const editItems = context.editable
    ? cellValueMenuItems({ editable: true, displayKind: context.displayKind, dbType: context.dbType, selectedRowCount: 0 })
      .filter((item) => item.action === 'set-null' || item.action === 'set-empty')
    : [];
  if (editItems.length > 0) {
    items.push({ type: 'divider' }, ...editItems.map((item) => ({ action: item.action as TableCellMenuAction, label: item.label, hint: item.hint, disabled: item.disabled })));
  }
  // 删除放在最后、单独一组：最危险的一项不该挨着「复制」被误点。
  if (context.rowAction) items.push({ type: 'divider' }, { action: 'toggle-delete', label: ROW_ACTION_LABELS[context.rowAction] });
  return items;
}

/** 由行状态推出删除类操作；与原先「操作」列按钮的三种状态一一对应。 */
export function tableRowDeleteAction(row: { deleted?: boolean; inserted?: boolean; rowDisabled?: boolean }): TableRowDeleteAction | undefined {
  if (row.rowDisabled) return undefined;
  if (row.deleted) return 'undo-delete';
  return row.inserted ? 'remove-inserted' : 'delete';
}

export type TableRowCopySource = {
  values: Record<string, unknown>;
  /** 新增行：只有填过的列才有值，其余列交给数据库默认值。 */
  inserted?: boolean;
  touchedColumns?: string[];
};

/**
 * 一行的复制文本。
 *
 * <p>新增行里没填过的列是 DEFAULT 而不是 NULL：生成 INSERT 时直接不写这些列，交给数据库的
 * 默认值，否则复制出来的语句会把有默认值的列显式写成 NULL；管道格式里它们照常写 NULL 占位，
 * 列数才对得上表头。</p>
 */
export function tableRowCopyText(
  format: 'pipe' | 'sql',
  columns: TableColumn[],
  row: TableRowCopySource,
  options: { dbType?: string; targetTableParts?: string[] } = {}
): string {
  const included = format === 'sql' && row.inserted
    ? columns.filter((column) => row.touchedColumns?.includes(column.name))
    : columns;
  if (included.length === 0) throw new Error('这一行还没有填写任何列，没有可复制的 INSERT 语句');
  const resultColumns = included.map((column) => ({ key: column.name, label: column.name, typeName: column.typeName }));
  const values = included.map((column) => row.values[column.name]);
  return serializeCopiedRows(format, resultColumns, [values], options).trimEnd();
}
