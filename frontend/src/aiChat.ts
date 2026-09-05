import type { AiChatMessage, AiClarifyQuestion, AiGroundingReference, AiGroundingReport } from './types';
import type { AiStreamEvent } from './aiSuggestion';

/**
 * SQL Agent 流的纯逻辑：事件归约、证据解析与会话键。
 *
 * <p>抽出来是因为这里的判断都值得单测 —— 尤其 answer-reset：Agent 每一轮都从头写正文，
 * 上一轮的开场白或没通过校验的候选 SQL 不能留在屏幕上，也不能混进最终答案。</p>
 */
export type AgentStreamState = {
  requestId?: string;
  conversationId?: string;
  answer: string;
  phase: string;
  grounding?: AiGroundingReport;
  activities: { name: string; summary: string; error: boolean }[];
  /** 模型正常收尾。 */
  done: boolean;
  /** 服务端明确报错，值是给用户看的文案。 */
  failure?: string;
  /** 请求被取消（用户点停止，或服务端关掉了这次运行）。 */
  cancelled: boolean;
  /** 这一轮以反问收尾：模型没给 SQL，而是问了一句。 */
  question?: AiClarifyQuestion;
};

export function initialAgentStreamState(phase = ''): AgentStreamState {
  return { answer: '', phase, activities: [], done: false, cancelled: false };
}

const KINDS = ['TABLE', 'COLUMN', 'FOREIGN_KEY', 'QUERY_HISTORY'] as const;

export function applyAgentEvent(state: AgentStreamState, event: AiStreamEvent): AgentStreamState {
  const data = event.data;
  switch (event.event) {
    case 'session':
      return {
        ...state,
        requestId: typeof data.requestId === 'string' ? data.requestId : state.requestId,
        conversationId: typeof data.conversationId === 'string' ? data.conversationId : state.conversationId
      };
    case 'answer-reset':
      // 新一轮开始：丢掉上一轮的正文和证据，工具痕迹保留（它们是这次请求做过的事）。
      return { ...state, answer: '', grounding: undefined };
    case 'delta':
      return typeof data.text === 'string' ? { ...state, answer: state.answer + data.text } : state;
    case 'phase':
      return typeof data.text === 'string' ? { ...state, phase: data.text } : state;
    case 'tool':
      return {
        ...state,
        activities: [...state.activities, {
          name: typeof data.name === 'string' ? data.name : 'metadata',
          summary: typeof data.summary === 'string' ? data.summary : '已检查数据库结构',
          error: data.error === true
        }]
      };
    case 'grounding':
      return { ...state, grounding: parseGrounding(data) };
    case 'question': {
      const question = parseQuestion(data);
      return question ? { ...state, question, phase: '' } : state;
    }
    case 'done':
      return { ...state, done: true, phase: '' };
    case 'failed':
      return { ...state, failure: typeof data.message === 'string' ? data.message : 'AI 调用失败' };
    case 'cancelled':
      return { ...state, cancelled: true };
    default:
      return state;
  }
}

export function parseGrounding(data: Record<string, unknown>): AiGroundingReport | undefined {
  if (typeof data.validated !== 'boolean' || typeof data.validationMessage !== 'string') return undefined;
  const references: AiGroundingReference[] = Array.isArray(data.references)
    ? data.references.flatMap((item) => {
      if (!item || typeof item !== 'object') return [];
      const value = item as Record<string, unknown>;
      if (!KINDS.includes(String(value.kind) as typeof KINDS[number]) || typeof value.label !== 'string') return [];
      return [{
        kind: value.kind as AiGroundingReference['kind'],
        label: value.label,
        detail: typeof value.detail === 'string' ? value.detail : undefined
      }];
    })
    : [];
  return { validated: data.validated, validationMessage: data.validationMessage, references };
}

/** 反问事件的解析。问题为空就当没问 —— 一个空气泡比没有更糟。 */
export function parseQuestion(data: Record<string, unknown>): AiClarifyQuestion | undefined {
  if (typeof data.question !== 'string' || !data.question.trim()) return undefined;
  const options = Array.isArray(data.options)
    ? data.options.flatMap((item) => {
      if (!item || typeof item !== 'object') return [];
      const value = item as Record<string, unknown>;
      if (typeof value.label !== 'string' || !value.label.trim()) return [];
      return [{ label: value.label, detail: typeof value.detail === 'string' ? value.detail : undefined }];
    })
    : [];
  return { question: data.question, options };
}

/**
 * 该把哪个反问画成按钮。
 *
 * <p>只认「对话里最后一条消息」上的那个：往上翻能看到几轮前问过什么，但那些问题早就被后面的
 * 回答接上了，再摆一排按钮出来，点下去等于把已经答过的问题重答一遍。刷新页面后从会话恢复的
 * 消息同样走这里 —— 用户不必把问题重读一遍再手打回答。</p>
 */
export function pendingQuestion(messages: AiChatMessage[], live?: AiClarifyQuestion): AiClarifyQuestion | undefined {
  if (live) return live;
  const last = messages[messages.length - 1];
  if (!last || last.role !== 'ASSISTANT') return undefined;
  return last.question || undefined;
}

export function conversationStorageKey(connectionId: number, schemaName?: string): string {
  return `mydatadev.ai.sql.${connectionId}.${schemaName || ''}`;
}

/** 从非 2xx 响应里挖出后端的中文错误文案，挖不到就退回状态行。 */
export function streamErrorMessage(payload: string, statusText: string): string {
  try {
    const parsed = JSON.parse(payload) as { message?: string };
    if (parsed.message) return parsed.message;
  } catch {
    if (payload.trim()) return payload.trim();
  }
  return statusText || 'AI 调用失败';
}

/**
 * 回答的分块渲染。
 *
 * <p>回答的固定形状是「一段说明 + 一个 ```sql 代码块」，而代码块按纯文本渲染时，反引号会原样
 * 显示、SQL 挤在正文字体里 —— 恰恰是这一屏最该看清的东西最难读。这里只解析代码围栏和两个行内
 * 标记，不做完整 Markdown：仓库一贯不为渲染引依赖（图表、XLSX、冒烟脚本都是手写的），而回答
 * 里实际会出现的也就这几种。</p>
 */
export type AnswerBlock =
  | { kind: 'text'; text: string }
  | { kind: 'code'; language: string; code: string };

const FENCE = /^```([\w+-]*)[ \t]*$/;

export function splitAnswerBlocks(answer: string): AnswerBlock[] {
  const blocks: AnswerBlock[] = [];
  if (!answer) return blocks;
  const lines = answer.split('\n');
  let text: string[] = [];
  let code: string[] | undefined;
  let language = '';

  const flushText = () => {
    const joined = text.join('\n').replace(/^\n+|\n+$/g, '');
    if (joined.trim()) blocks.push({ kind: 'text', text: joined });
    text = [];
  };

  for (const line of lines) {
    const fence = FENCE.exec(line.trim());
    if (code) {
      if (fence) {
        blocks.push({ kind: 'code', language, code: code.join('\n') });
        code = undefined;
        continue;
      }
      code.push(line);
      continue;
    }
    if (fence) {
      flushText();
      language = fence[1] || '';
      code = [];
      continue;
    }
    text.push(line);
  }
  // 还没收尾的围栏照样按代码渲染：流式输出时收尾的 ``` 本来就还没到，
  // 若退回纯文本，用户会看着 SQL 先以正文字体出现再整段跳成代码块。
  if (code) blocks.push({ kind: 'code', language, code: code.join('\n') });
  else flushText();
  return blocks;
}

/** 行内标记：只认 `**加粗**` 和 `` `代码` ``，其余原样保留。 */
export type InlineSpan = { kind: 'plain' | 'strong' | 'code'; text: string };

const INLINE = /(\*\*(?!\s)(?:[^*]|\*(?!\*))+?\*\*)|(`[^`\n]+`)/g;

export function splitInlineSpans(text: string): InlineSpan[] {
  const spans: InlineSpan[] = [];
  let cursor = 0;
  for (const match of text.matchAll(INLINE)) {
    const index = match.index ?? 0;
    if (index > cursor) spans.push({ kind: 'plain', text: text.slice(cursor, index) });
    if (match[1]) spans.push({ kind: 'strong', text: match[1].slice(2, -2) });
    else spans.push({ kind: 'code', text: match[2].slice(1, -1) });
    cursor = index + match[0].length;
  }
  if (cursor < text.length) spans.push({ kind: 'plain', text: text.slice(cursor) });
  return spans.length ? spans : [{ kind: 'plain', text }];
}
