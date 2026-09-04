import { describe, expect, it } from 'vitest';
import {
  applyStreamEvent,
  consumeSseBuffer,
  extractSqlBlocks,
  firstSqlBlock,
  hasUnclosedSqlFence,
  isAiAvailableForConnection
} from './aiSuggestion';

describe('SSE 分片', () => {
  it('切出完整事件并退回残片', () => {
    const { events, rest } = consumeSseBuffer('event:delta\ndata:{"text":"你好"}\n\nevent:delta\ndata:{"text":"世');

    expect(events).toEqual([{ event: 'delta', data: { text: '你好' } }]);
    expect(rest).toBe('event:delta\ndata:{"text":"世');
  });

  it('跨分片的事件拼起来之后才算数', () => {
    const first = consumeSseBuffer('event:delta\ndata:{"te');
    expect(first.events).toHaveLength(0);

    const second = consumeSseBuffer(first.rest + 'xt":"界"}\n\n');
    expect(second.events).toEqual([{ event: 'delta', data: { text: '界' } }]);
  });

  it('忽略心跳注释行', () => {
    expect(consumeSseBuffer(':keep-alive\n\n').events).toHaveLength(0);
  });

  it('事件体不是 JSON 时当纯文本处理，不丢事件', () => {
    expect(consumeSseBuffer('event:delta\ndata:不是JSON\n\n').events).toEqual([
      { event: 'delta', data: { text: '不是JSON' } }
    ]);
  });

  it('兼容 CRLF 换行', () => {
    expect(consumeSseBuffer('event:done\r\ndata:{"ok":true}\r\n\r\n').events).toEqual([
      { event: 'done', data: { ok: true } }
    ]);
  });
});

describe('事件折叠成界面状态', () => {
  const initial = { text: '', done: false } as { text: string; done: boolean; error?: string };

  it('增量按顺序拼接', () => {
    const one = applyStreamEvent(initial, { event: 'delta', data: { text: '第一段' } });
    const two = applyStreamEvent(one, { event: 'delta', data: { text: '第二段' } });

    expect(two.text).toBe('第一段第二段');
    expect(two.done).toBe(false);
  });

  it('done 事件结束这一轮', () => {
    expect(applyStreamEvent(initial, { event: 'done', data: { ok: true } }).done).toBe(true);
  });

  it('failed 事件带出上游原因并结束这一轮', () => {
    const state = applyStreamEvent(initial, { event: 'failed', data: { message: '上游限流' } });

    expect(state).toMatchObject({ done: true, error: '上游限流' });
  });

  it('未知事件不改变状态', () => {
    expect(applyStreamEvent(initial, { event: 'whatever', data: {} })).toEqual(initial);
  });
});

describe('回答里的 SQL', () => {
  it('取出 ```sql 围栏里的语句', () => {
    const answer = '原因是表名写错了。\n\n```sql\nSELECT * FROM orders;\n```\n\n以上。';

    expect(extractSqlBlocks(answer)).toEqual(['SELECT * FROM orders;']);
    expect(firstSqlBlock(answer)).toBe('SELECT * FROM orders;');
  });

  /** 无标注代码块常常是报错原文或执行计划，当成 SQL 塞进编辑器比不给更糟。 */
  it('不认无标注的代码块', () => {
    expect(extractSqlBlocks('```\nERROR: relation does not exist\n```')).toEqual([]);
  });

  it('多个代码块按出现顺序返回', () => {
    expect(extractSqlBlocks('```sql\nSELECT 1;\n```\n说明\n```sql\nSELECT 2;\n```')).toEqual(['SELECT 1;', 'SELECT 2;']);
  });

  it('流式过程中未闭合的围栏也能取到内容，但会被标成未收尾', () => {
    const partial = '```sql\nSELECT * FROM ord';

    expect(firstSqlBlock(partial)).toBe('SELECT * FROM ord');
    expect(hasUnclosedSqlFence(partial)).toBe(true);
    expect(hasUnclosedSqlFence('```sql\nSELECT 1;\n```')).toBe(false);
  });
});

describe('可用性判断', () => {
  it('功能关着时任何连接都不显示入口', () => {
    expect(isAiAvailableForConnection({ enabled: false, sharedConnectionIds: [1] }, 1)).toBe(false);
  });

  it('只有被授权的连接才显示入口', () => {
    const status = { enabled: true, sharedConnectionIds: [1, 3] };

    expect(isAiAvailableForConnection(status, 1)).toBe(true);
    expect(isAiAvailableForConnection(status, 2)).toBe(false);
    expect(isAiAvailableForConnection(status, null)).toBe(false);
    expect(isAiAvailableForConnection(undefined, 1)).toBe(false);
  });
});
