import { describe, expect, it } from 'vitest';
import {
  applyAgentEvent,
  conversationStorageKey,
  initialAgentStreamState,
  parseGrounding,
  splitAnswerBlocks,
  splitInlineSpans,
  streamErrorMessage
} from './aiChat';
import { consumeSseBuffer } from './aiSuggestion';
import type { AgentStreamState } from './aiChat';

function replay(chunks: string[]): AgentStreamState {
  let state = initialAgentStreamState();
  let buffer = '';
  for (const chunk of chunks) {
    buffer += chunk;
    const parsed = consumeSseBuffer(buffer);
    buffer = parsed.rest;
    for (const event of parsed.events) state = applyAgentEvent(state, event);
  }
  return state;
}

function sse(event: string, data: unknown): string {
  return `event:${event}\ndata:${JSON.stringify(data)}\n\n`;
}

describe('applyAgentEvent', () => {
  it('拼接正文增量并记录会话与工具痕迹', () => {
    const state = replay([
      sse('session', { requestId: 'r-1', conversationId: 'c-1' }),
      sse('phase', { text: '正在检查结构…' }),
      sse('tool', { name: 'search_schema', summary: '找到 3 个候选对象', error: false }),
      sse('delta', { text: '```sql\nSELECT ' }),
      sse('delta', { text: 'id FROM app_user\n```' }),
      sse('done', { ok: true })
    ]);

    expect(state.requestId).toBe('r-1');
    expect(state.conversationId).toBe('c-1');
    expect(state.answer).toBe('```sql\nSELECT id FROM app_user\n```');
    expect(state.activities).toEqual([
      { name: 'search_schema', summary: '找到 3 个候选对象', error: false }
    ]);
    expect(state.done).toBe(true);
    expect(state.phase).toBe('');
  });

  it('每轮开始时丢掉上一轮的正文，没通过校验的候选 SQL 不会留在答案里', () => {
    const state = replay([
      sse('answer-reset', { round: 1 }),
      sse('delta', { text: '先看看表结构' }),
      sse('tool', { name: 'describe_objects', summary: '读取了 2 个对象', error: false }),
      sse('answer-reset', { round: 2 }),
      sse('delta', { text: '```sql\nSELECT wrong FROM t\n```' }),
      sse('grounding', { validated: false, validationMessage: '编译失败', references: [] }),
      sse('answer-reset', { round: 3 }),
      sse('delta', { text: '```sql\nSELECT id FROM t\n```' }),
      sse('grounding', { validated: true, validationMessage: '校验通过', references: [] }),
      sse('done', { ok: true })
    ]);

    expect(state.answer).toBe('```sql\nSELECT id FROM t\n```');
    expect(state.grounding?.validated).toBe(true);
    // 工具痕迹是「这次请求做过什么」，跨轮保留。
    expect(state.activities).toHaveLength(1);
  });

  it('分别记录失败与取消', () => {
    expect(replay([sse('failed', { message: '上游返回 429' })]).failure).toBe('上游返回 429');
    expect(replay([sse('cancelled', { message: 'AI 请求已取消' })]).cancelled).toBe(true);
    expect(replay([sse('failed', {})]).failure).toBe('AI 调用失败');
  });

  it('忽略形状不对的事件而不是把 undefined 拼进正文', () => {
    const state = replay([sse('delta', { text: 42 }), sse('phase', {}), sse('unknown', { text: 'x' })]);

    expect(state.answer).toBe('');
    expect(state.phase).toBe('');
  });
});

describe('parseGrounding', () => {
  it('筛掉未知种类和缺字段的证据', () => {
    const report = parseGrounding({
      validated: true,
      validationMessage: '通过',
      references: [
        { kind: 'TABLE', label: 'app_user', detail: '用户表' },
        { kind: 'COLUMN', label: 'app_user.id' },
        { kind: 'INDEX', label: 'idx_user' },
        { kind: 'TABLE' },
        'nonsense'
      ]
    });

    expect(report?.references).toEqual([
      { kind: 'TABLE', label: 'app_user', detail: '用户表' },
      { kind: 'COLUMN', label: 'app_user.id', detail: undefined }
    ]);
  });

  it('缺少校验结论时不生成报告', () => {
    expect(parseGrounding({ validationMessage: '通过', references: [] })).toBeUndefined();
    expect(parseGrounding({ validated: true, references: [] })).toBeUndefined();
  });
});

describe('conversationStorageKey', () => {
  it('按连接与命名空间分开存，不同 schema 不会串会话', () => {
    expect(conversationStorageKey(3, 'public')).toBe('mydatadev.ai.sql.3.public');
    expect(conversationStorageKey(3, 'sales')).not.toBe(conversationStorageKey(3, 'public'));
    expect(conversationStorageKey(3)).toBe('mydatadev.ai.sql.3.');
  });
});

describe('streamErrorMessage', () => {
  it('优先用后端的中文文案', () => {
    expect(streamErrorMessage('{"message":"这条连接尚未授权给 AI 使用"}', 'Forbidden'))
      .toBe('这条连接尚未授权给 AI 使用');
    expect(streamErrorMessage('nginx 502', 'Bad Gateway')).toBe('nginx 502');
    expect(streamErrorMessage('', 'Bad Gateway')).toBe('Bad Gateway');
    expect(streamErrorMessage('', '')).toBe('AI 调用失败');
  });
});

describe('splitAnswerBlocks', () => {
  it('把说明和 SQL 代码块分开，反引号不再出现在正文里', () => {
    const blocks = splitAnswerBlocks('原因是字段名不对。\n\n```sql\nSELECT CUST_NM\nFROM T_CRM_0021\n```\n\n已通过校验。');

    expect(blocks).toEqual([
      { kind: 'text', text: '原因是字段名不对。' },
      { kind: 'code', language: 'sql', code: 'SELECT CUST_NM\nFROM T_CRM_0021' },
      { kind: 'text', text: '已通过校验。' }
    ]);
  });

  it('流式时还没收尾的围栏也按代码渲染，避免整段从正文跳成代码块', () => {
    const blocks = splitAnswerBlocks('先看结构：\n```sql\nSELECT CUST_NM FROM');

    expect(blocks).toEqual([
      { kind: 'text', text: '先看结构：' },
      { kind: 'code', language: 'sql', code: 'SELECT CUST_NM FROM' }
    ]);
  });

  it('没有代码块时整段就是一块正文', () => {
    expect(splitAnswerBlocks('这条 SQL 的问题在权限，不在结构。'))
      .toEqual([{ kind: 'text', text: '这条 SQL 的问题在权限，不在结构。' }]);
    expect(splitAnswerBlocks('')).toEqual([]);
    expect(splitAnswerBlocks('   \n  ')).toEqual([]);
  });

  it('不给语言的围栏也认，代码块内部的空行保留', () => {
    expect(splitAnswerBlocks('```\nline1\n\nline2\n```'))
      .toEqual([{ kind: 'code', language: '', code: 'line1\n\nline2' }]);
  });

  it('多个代码块按出现顺序保留', () => {
    const blocks = splitAnswerBlocks('一：\n```sql\nSELECT 1\n```\n二：\n```sql\nSELECT 2\n```');

    expect(blocks.filter((block) => block.kind === 'code')).toHaveLength(2);
    expect(blocks.map((block) => block.kind)).toEqual(['text', 'code', 'text', 'code']);
  });
});

describe('splitInlineSpans', () => {
  it('识别加粗与行内代码，其余原样', () => {
    expect(splitInlineSpans('真实表名是 `T_CRM_0021`，**不是** customer')).toEqual([
      { kind: 'plain', text: '真实表名是 ' },
      { kind: 'code', text: 'T_CRM_0021' },
      { kind: 'plain', text: '，' },
      { kind: 'strong', text: '不是' },
      { kind: 'plain', text: ' customer' }
    ]);
  });

  it('落单的星号和反引号不当作标记', () => {
    expect(splitInlineSpans('SELECT * FROM t -- 3 ** 2')).toEqual([
      { kind: 'plain', text: 'SELECT * FROM t -- 3 ** 2' }
    ]);
    expect(splitInlineSpans('未闭合的 `反引号')).toEqual([{ kind: 'plain', text: '未闭合的 `反引号' }]);
  });

  it('空文本返回一段空的纯文本而不是空数组', () => {
    expect(splitInlineSpans('')).toEqual([{ kind: 'plain', text: '' }]);
  });
});
