import type { AiGroundingReference, AiGroundingReport } from './types';
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
