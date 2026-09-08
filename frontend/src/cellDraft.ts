/**
 * 单元格编辑框与显示态之间那层共用的判定。
 *
 * <p>查询结果表格（`ResultGrid`）和表数据工作区（`EditableTable`）各有一套单元格实现 ——
 * 前者按「行下标 : 列名」定位、后者按行 id 加上一份列元数据，还多出 DEFAULT 态、类型校验、
 * 大文本弹窗和截断列禁用。硬抽一个同时满足两边的组件会拖出一长串可选 prop，比两份各自简单的
 * 实现更难读。所以共享的是这里这几个纯函数，组件各写各的。</p>
 *
 * <p>这个模块存在的直接原因是一个真实的 bug：结果表格里点一下 NULL 单元格再点走，就会凭空
 * 多出一条 `NULL → 空字符串` 的待提交修改 —— 输入框把 NULL 渲染成空文本，失焦时又无条件把
 * 这个空文本当成用户输入提交了。NULL 和空字符串在库里是两个值，不能靠「输入框里看起来一样」
 * 把它们混过去。</p>
 */

/** 单元格显示态的三种形态。两个表格用同一套判定，界面上才可能给出同一套记号。 */
export type CellDisplayKind = 'null' | 'empty' | 'value';

export function cellDisplayKind(value: unknown): CellDisplayKind {
  if (value == null) return 'null';
  if (value === '') return 'empty';
  return 'value';
}

/**
 * 编辑框的初始文本。
 *
 * <p>NULL 与空字符串在这里都是空文本 —— 一个文本框表达不了两者的区别，这是事实，不该假装
 * 能表达。区别靠 {@link cellDraftChanged} 记住「进来时是什么」，以及右键菜单里那两个明确的
 * 动作来表达。</p>
 */
export function cellDraftText(value: unknown): string {
  return value == null ? '' : String(value);
}

/**
 * 失焦时这次编辑算不算一处改动。
 *
 * <p>规则只有一条：文本没动过就不算。于是「点开一个 NULL 格子什么都没输入」不再造出修改，
 * 而「把一个有值的格子清空」仍然是一次合法的改成空字符串 —— 后者文本确实变了。</p>
 *
 * <p>刻意不在这里比较语义值（那是 `resultEditing.applyResultCellEdit` 用 `sameCellValue`
 * 做的事，改回原值会自动撤销那条记录）。这里只回答「用户到底有没有编辑」。</p>
 */
export function cellDraftChanged(draft: string, initial: string): boolean {
  return draft !== initial;
}
