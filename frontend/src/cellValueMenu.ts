/**
 * 单元格右键菜单的内容判定。
 *
 * <p>一个输入框表达不了「NULL」和「空字符串」的区别 —— 两者在框里都是空的。所以这两个值必须
 * 有各自明确的入口，而不是靠「清空输入框算哪一个」这种猜。这里决定菜单上出现哪几项，渲染交给
 * 组件。</p>
 */

import type { CellDisplayKind } from './cellDraft';

/**
 * 这个数据库把空字符串存成 NULL 吗？
 *
 * <p>Oracle 系是这样：`''` 写进 VARCHAR2 读出来就是 NULL，两个值在库里根本不可区分。菜单上
 * 得说清楚，否则用户点了「设为空字符串」、提交后看到的还是 NULL，会当成这个功能坏了。</p>
 */
export function emptyStringMeansNull(dbType?: string): boolean {
  if (!dbType) return false;
  return ['oracle', 'oceanbase-oracle', 'dm'].includes(dbType.toLowerCase());
}

export type CellValueMenuAction = 'set-null' | 'set-empty' | 'copy' | 'set-null-in-selection';

export type CellValueMenuItem = {
  action: CellValueMenuAction;
  label: string;
  /** 需要额外解释时给一句提示，没有就不显示。 */
  hint?: string;
  disabled?: boolean;
};

export type CellValueMenuContext = {
  /** 这一列能不能改。行定位列和只读连接上都是 false。 */
  editable: boolean;
  displayKind: CellDisplayKind;
  dbType?: string;
  /** 右键点在一行已选中的行上时，选中的行数；否则 0。批量项只在 >1 时才有意义。 */
  selectedRowCount: number;
};

export function cellValueMenuItems(context: CellValueMenuContext): CellValueMenuItem[] {
  const items: CellValueMenuItem[] = [];
  if (context.editable) {
    const emptyIsNull = emptyStringMeansNull(context.dbType);
    items.push({
      action: 'set-null',
      label: '设为 NULL',
      // 已经是 NULL 还给这一项没有意义，但留着比消失好认 —— 置灰即可。
      disabled: context.displayKind === 'null'
    });
    items.push({
      action: 'set-empty',
      label: '设为空字符串',
      disabled: context.displayKind === 'empty',
      hint: emptyIsNull ? '这个数据库把空字符串存成 NULL，提交后仍会显示 NULL' : undefined
    });
    if (context.selectedRowCount > 1) {
      items.push({
        action: 'set-null-in-selection',
        label: `把本列在选中的 ${context.selectedRowCount} 行上设为 NULL`
      });
    }
  }
  items.push({ action: 'copy', label: '复制单元格值' });
  return items;
}
