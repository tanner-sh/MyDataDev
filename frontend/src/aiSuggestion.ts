/**
 * AI 回答的解析。
 *
 * 流式分片怎么拼、回答里的 SQL 怎么取出来、哪条连接上该显示 AI 入口 —— 都是纯逻辑，
 * 放在组件外面才测得到。面板只负责发请求和画界面。
 */

import type { AiStatus } from './types';

export type AiStreamEvent = { event: string; data: Record<string, unknown> };

/**
 * 从 SSE 字节流的缓冲区里切出完整事件。
 *
 * 分片可能在任何位置断开（甚至断在 `data:` 中间），所以只处理以空行结尾的完整块，
 * 剩下的原样退回给调用方接着攒。
 */
export function consumeSseBuffer(buffer: string): { events: AiStreamEvent[]; rest: string } {
  const events: AiStreamEvent[] = [];
  let rest = buffer.replace(/\r\n/g, '\n');
  let boundary = rest.indexOf('\n\n');
  while (boundary >= 0) {
    const block = rest.slice(0, boundary);
    rest = rest.slice(boundary + 2);
    const parsed = parseSseBlock(block);
    if (parsed) events.push(parsed);
    boundary = rest.indexOf('\n\n');
  }
  return { events, rest };
}

function parseSseBlock(block: string): AiStreamEvent | undefined {
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) continue; // 心跳注释
    if (line.startsWith('event:')) event = line.slice('event:'.length).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice('data:'.length).trim());
  }
  if (dataLines.length === 0) return undefined;
  try {
    const parsed = JSON.parse(dataLines.join('\n')) as unknown;
    return { event, data: parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {} };
  } catch {
    // 事件体不是 JSON 就当纯文本，别让一行坏数据把整段回答弄丢。
    return { event, data: { text: dataLines.join('\n') } };
  }
}

/** 把事件流折叠成界面要显示的状态。 */
export function applyStreamEvent(
  current: { text: string; done: boolean; error?: string },
  event: AiStreamEvent
): { text: string; done: boolean; error?: string } {
  if (event.event === 'delta') {
    const delta = typeof event.data.text === 'string' ? event.data.text : '';
    return { ...current, text: current.text + delta };
  }
  if (event.event === 'done') return { ...current, done: true };
  if (event.event === 'failed') {
    return { ...current, done: true, error: typeof event.data.message === 'string' ? event.data.message : 'AI 调用失败' };
  }
  return current;
}

/**
 * 取出回答里的 SQL 代码块。
 *
 * 只认 ```sql 围栏：无标注的代码块可能是报错原文或计划输出，把它当 SQL 塞进编辑器
 * 比不给更糟。
 */
export function extractSqlBlocks(markdown: string): string[] {
  if (!markdown) return [];
  const blocks: string[] = [];
  const fence = /```sql\s*\n([\s\S]*?)(?:```|$)/gi;
  let match = fence.exec(markdown);
  while (match) {
    const sql = match[1].trim();
    if (sql) blocks.push(sql);
    match = fence.exec(markdown);
  }
  return blocks;
}

/** 回答里的第一条 SQL —— 「插入编辑器」按钮用它。 */
export function firstSqlBlock(markdown: string): string | undefined {
  return extractSqlBlocks(markdown)[0];
}

/** 流式回答的最后一个代码块可能还没收尾，据此让「插入」按钮先等一等。 */
export function hasUnclosedSqlFence(markdown: string): boolean {
  const fences = markdown.match(/```/g);
  return fences ? fences.length % 2 === 1 : false;
}

export function isAiAvailableForConnection(status: AiStatus | undefined, connectionId?: number | null): boolean {
  if (!status?.enabled || connectionId == null) return false;
  return status.sharedConnectionIds.includes(connectionId);
}
