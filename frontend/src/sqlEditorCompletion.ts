/**
 * 补全结果在「工作台的形状」与「编辑器的形状」之间的翻译。
 *
 * <p>从 .tsx 里抽出来是为了能测：这里有几条容易写错又不会立刻报错的规则 ——
 * 触发字符怎么算、什么时候允许编辑器在本地过滤、列为什么要排在表前面。写错了不会崩，
 * 只是补全「感觉不对」，而那种问题最难在代码里看出来。</p>
 */
import type { SqlCompletionItem, SqlCompletionResult } from './sqlEditorTypes';

/** 编辑器补全项的形状；与 @codemirror/autocomplete 的 Completion 对齐。 */
export type EditorCompletionOption = {
  label: string;
  apply: string;
  type: string;
  detail?: string;
  boost: number;
};

export type EditorCompletionResult = {
  from: number;
  to: number;
  validFor?: RegExp;
  options: EditorCompletionOption[];
};

const COMPLETION_TYPES: Record<SqlCompletionItem['kind'], string> = {
  table: 'class',
  column: 'property',
  schema: 'namespace',
  keyword: 'keyword'
};

/**
 * 光标前那个字符算不算触发字符。
 *
 * <p>只认 '.' 和 ' '，与换编辑器之前的 triggerCharacters 一致：'.' 之后要补字段，
 * 空格之后只有「条件里该补列名」这一种情况值得弹窗，别的场景由上层判掉。</p>
 */
export function completionTriggerCharacter(text: string, offset: number, explicit = false): string | undefined {
  // Ctrl/Cmd+Space 是用户明确要求展示候选项，不能因为光标刚好落在空格后面而被当成
  // 自动触发，否则上层的「只在条件关键字后自动弹出」规则会误伤显式补全。
  if (explicit) return undefined;
  if (offset <= 0 || offset > text.length) return undefined;
  const previous = text[offset - 1];
  return previous === '.' || previous === ' ' ? previous : undefined;
}

/**
 * 结果完整时给出的本地过滤规则。
 *
 * <p>返回 undefined 表示「继续输入要重新问一次」。引号与限定符（"orders"、`user`、
 * [orders]、public.orders）算在同一个补全词里，否则用户一敲引号弹窗就关了。</p>
 *
 * <p>空格不在其中：正常输入里空格几乎总是意味着换到下一个词。代价是带空格的标识符
 * （"order by"）会多问一次服务端，但让弹窗跨空格存活会误伤得多。</p>
 */
export function completionValidFor(incomplete: boolean): RegExp | undefined {
  return incomplete ? undefined : /^[\w$."`[\]]*$/;
}

export function toEditorCompletion(result: SqlCompletionResult): EditorCompletionResult {
  return {
    from: result.range.start,
    to: result.range.end,
    validFor: completionValidFor(result.incomplete),
    options: result.items.map((item) => ({
      label: item.label,
      apply: item.insertText,
      type: COMPLETION_TYPES[item.kind],
      detail: item.detail,
      // sortText 以 '0-' 开头的是列：查询里正缺的通常是列名，让它排在表和关键字前面。
      boost: item.sortText?.startsWith('0-') ? 1 : 0
    }))
  };
}
