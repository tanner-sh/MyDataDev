/**
 * SQL 工作台上下分栏的默认比例。
 *
 * 之前是固定 0.52：一条单行 SQL 也要占掉 52% 的高度，而结果区只剩不到一半 —— 在
 * 1600×1000 上 120 行结果只看得见 9 行，而同屏的表数据工作区能显示 23 行。编辑器该多高
 * 取决于里面有多少行 SQL，不该是个常数。
 *
 * 规则：用户手动拖过分隔条就完全听用户的；没拖过时按内容推算 —— 没有结果时编辑器占大头，
 * 有结果时按 SQL 实际行数给高度并夹在一个区间内，保证结果区拿到大部分空间。
 */

/** 与 SqlWorkspace 的 EDITOR_OPTIONS.lineHeight 保持一致。 */
export const EDITOR_LINE_HEIGHT = 22;
/** EDITOR_OPTIONS.padding 的上下之和，再加一点呼吸空间。 */
export const EDITOR_VERTICAL_PADDING = 32;

/** 没有结果时编辑器占的比例。 */
export const EDITOR_RATIO_WITHOUT_RESULTS = 0.72;
/** 有结果时编辑器的比例下限，保证短 SQL 也看得清。 */
export const EDITOR_RATIO_WITH_RESULTS_MIN = 0.22;
/** 有结果时编辑器的比例上限，保证结果区始终是主角。 */
export const EDITOR_RATIO_WITH_RESULTS_MAX = 0.45;

export function countSqlLines(sql: string): number {
  if (!sql) return 1;
  let lines = 1;
  for (let index = 0; index < sql.length; index += 1) {
    if (sql[index] === '\n') lines += 1;
  }
  return lines;
}

export type EditorSplitInput = {
  /** 用户是否手动拖过分隔条。 */
  touched: boolean;
  /** 存储中的比例，仅在 touched 时使用。 */
  storedRatio: number;
  hasResults: boolean;
  sql: string;
  /** 分栏容器的可用高度；未测量到时退回按比例估算。 */
  containerHeight?: number;
};

export function resolveEditorSplitRatio({
  touched,
  storedRatio,
  hasResults,
  sql,
  containerHeight
}: EditorSplitInput): number {
  if (touched) return storedRatio;
  if (!hasResults) return EDITOR_RATIO_WITHOUT_RESULTS;

  const contentHeight = countSqlLines(sql) * EDITOR_LINE_HEIGHT + EDITOR_VERTICAL_PADDING;
  const ratio = containerHeight && containerHeight > 0
    ? contentHeight / containerHeight
    : EDITOR_RATIO_WITH_RESULTS_MIN;
  return clamp(ratio, EDITOR_RATIO_WITH_RESULTS_MIN, EDITOR_RATIO_WITH_RESULTS_MAX);
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, value));
}
