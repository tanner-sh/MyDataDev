/**
 * 补全结果在「工作台的形状」与「编辑器的形状」之间的翻译。
 *
 * <p>从 .tsx 里抽出来是为了能测：这里有几条容易写错又不会立刻报错的规则 ——
 * 触发字符怎么算、什么时候允许编辑器在本地过滤、列为什么要排在表前面。写错了不会崩，
 * 只是补全「感觉不对」，而那种问题最难在代码里看出来。</p>
 */
import type { SqlCompletionItem, SqlCompletionResult } from './sqlEditorTypes';
import { pickedCompletion, type Completion } from '@codemirror/autocomplete';
import { EditorSelection } from '@codemirror/state';

/** 编辑器补全项的形状；与 @codemirror/autocomplete 的 Completion 对齐。 */
export type EditorCompletionOption = {
  label: string;
  apply: Completion['apply'];
  type: string;
  detail?: string;
  remarks?: string;
  info?: string;
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
 * <p>把换行、制表符与空格统一交给条件上下文判断。</p>
 */
export function completionTriggerCharacter(text: string, offset: number, explicit = false): string | undefined {
  // Ctrl/Cmd+Space 是用户明确要求展示候选项，不能因为光标刚好落在空格后面而被当成
  // 自动触发，否则上层的「只在条件关键字后自动弹出」规则会误伤显式补全。
  if (explicit) return undefined;
  if (offset <= 0 || offset > text.length) return undefined;
  const previous = text[offset - 1];
  return previous === '.' ? '.' : /\s/u.test(previous) ? ' ' : undefined;
}

const CONDITION_KEYWORDS = new Set(['WHERE', 'ON', 'HAVING', 'AND', 'OR']);

export function isConditionKeywordCompletion(completion: Pick<Completion, 'type' | 'label'>): boolean {
  return completion.type === 'keyword' && CONDITION_KEYWORDS.has(completion.label.toUpperCase());
}

/** 复用已有空白，并把光标移到字段输入位置，避免 WHERE 与字段粘连。 */
export function conditionKeywordInsertion(text: string, to: number, keyword: string) {
  const whitespace = /^\s*/u.exec(text.slice(to))![0];
  return { insert: keyword + (whitespace ? '' : ' '), followingWhitespace: whitespace.length };
}

const applyConditionKeyword: NonNullable<Exclude<Completion['apply'], string>> = (view, completion, from, to) => {
  const { state } = view;
  const { main } = state.selection;
  const fromOffset = from - main.from, toOffset = to - main.from;
  const text = state.doc.toString();
  view.dispatch({
    ...state.changeByRange(range => {
      const start = range.from + fromOffset;
      const end = to === main.from ? range.to : range.from + toOffset;
      if (range !== main && from !== to && state.sliceDoc(start, end) !== state.sliceDoc(from, to)) return { range };
      const { insert, followingWhitespace } = conditionKeywordInsertion(text, end, completion.label);
      return {
        changes: { from: start, to: end, insert },
        range: EditorSelection.cursor(start + insert.length + followingWhitespace)
      };
    }),
    scrollIntoView: true,
    userEvent: 'input.complete',
    annotations: pickedCompletion.of(completion)
  });
};

/**
 * 结果完整时给出的本地过滤规则。
 *
 * <p>返回 undefined 表示「继续输入要重新问一次」。引号标识符（"orders"、`user`、
 * [orders]）算在同一个补全词里；点号切换到限定字段，必须重新解析上下文。</p>
 *
 * <p>空格不在其中：正常输入里空格几乎总是意味着换到下一个词。代价是带空格的标识符
 * （"order by"）会多问一次服务端，但让弹窗跨空格存活会误伤得多。</p>
 */
export function completionValidFor(incomplete: boolean): RegExp | undefined {
  return incomplete ? undefined : /^[\w$"`[\]]*$/;
}

export function toEditorCompletion(result: SqlCompletionResult): EditorCompletionResult {
  return {
    from: result.range.start,
    to: result.range.end,
    validFor: completionValidFor(result.incomplete),
    options: result.items.map((item) => ({
      label: item.label,
      apply: item.kind === 'keyword' && CONDITION_KEYWORDS.has(item.label.toUpperCase()) ? applyConditionKeyword : item.insertText,
      type: COMPLETION_TYPES[item.kind],
      detail: item.detail,
      remarks: item.remarks,
      info: item.remarks ? `${item.label}\n${item.remarks}\n${item.detail || ''}` : undefined,
      // sortText 以 '0-' 开头的是列：查询里正缺的通常是列名，让它排在表和关键字前面。
      boost: item.sortText?.startsWith('0-') ? 1 : 0
    }))
  };
}
