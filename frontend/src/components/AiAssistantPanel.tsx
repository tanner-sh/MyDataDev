import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Drawer, Space, Typography } from 'antd';
import { CheckOutlined, CopyOutlined, ImportOutlined, StopOutlined } from '@ant-design/icons';
import { API } from '../constants';
import { authHeaders } from '../auth';
import { applyStreamEvent, consumeSseBuffer, firstSqlBlock, hasUnclosedSqlFence } from '../aiSuggestion';
import { PanelLoading } from './PanelState';

const { Paragraph, Text } = Typography;

export type AiAskRequest = {
  /** 后端路径（相对 /api/ai/sql），例如 diagnose。 */
  action: string;
  title: string;
  body: Record<string, unknown>;
};

/**
 * AI 回答抽屉。
 *
 * 用 fetch 读流而不是 EventSource：请求要带上整条 SQL 与报错原文，EventSource 只能发 GET。
 * 解析与状态折叠都在 aiSuggestion.ts 里，这里只负责发请求、画界面和把 SQL 交回编辑器。
 */
export function AiAssistantPanel({ request, onClose, onInsertSql }: {
  request?: AiAskRequest;
  onClose: () => void;
  onInsertSql?: (sql: string) => void;
}) {
  const [state, setState] = useState<{ text: string; done: boolean; error?: string }>({ text: '', done: false });
  const [copied, setCopied] = useState(false);
  const abortRef = useRef<AbortController | undefined>(undefined);

  const ask = useCallback(async (ask: AiAskRequest) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setState({ text: '', done: false });
    try {
      const response = await fetch(`${API}/ai/sql/${ask.action}/stream`, {
        method: 'POST',
        credentials: 'include',
        signal: controller.signal,
        headers: { 'Content-Type': 'application/json', 'X-User': 'admin', ...authHeaders('POST') },
        body: JSON.stringify(ask.body)
      });
      if (!response.ok || !response.body) {
        const payload = await response.text();
        let message = response.statusText;
        try {
          const parsed = JSON.parse(payload) as { message?: string };
          if (parsed.message) message = parsed.message;
        } catch {
          if (payload.trim()) message = payload.trim();
        }
        setState({ text: '', done: true, error: message });
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const { events, rest } = consumeSseBuffer(buffer);
        buffer = rest;
        if (events.length > 0) setState((current) => events.reduce(applyStreamEvent, current));
      }
      setState((current) => (current.done ? current : { ...current, done: true }));
    } catch (cause) {
      if (controller.signal.aborted) return;
      setState({ text: '', done: true, error: cause instanceof Error ? cause.message : 'AI 调用失败' });
    }
  }, []);

  useEffect(() => {
    if (request) void ask(request);
    return () => abortRef.current?.abort();
  }, [request, ask]);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1_600);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const sql = firstSqlBlock(state.text);
  const sqlReady = Boolean(sql) && !hasUnclosedSqlFence(state.text);

  return (
    <Drawer
      open={Boolean(request)}
      title={request?.title || 'AI 助手'}
      width={520}
      onClose={() => { abortRef.current?.abort(); onClose(); }}
      extra={
        <Space size={4}>
          {!state.done && (
            <Button size="small" icon={<StopOutlined />} onClick={() => { abortRef.current?.abort(); setState((current) => ({ ...current, done: true })); }}>
              停止
            </Button>
          )}
          <Button
            size="small"
            icon={copied ? <CheckOutlined /> : <CopyOutlined />}
            disabled={!state.text}
            onClick={() => { void navigator.clipboard?.writeText(state.text).then(() => setCopied(true)).catch(() => undefined); }}
          >
            {copied ? '已复制' : '复制回答'}
          </Button>
          {onInsertSql && (
            <Button size="small" type="primary" icon={<ImportOutlined />} disabled={!sqlReady} onClick={() => sql && onInsertSql(sql)}>
              插入编辑器
            </Button>
          )}
        </Space>
      }
    >
      {state.error && <Alert type="error" showIcon message="AI 调用失败" description={state.error} style={{ marginBottom: 12 }} />}
      {!state.text && !state.done && !state.error && <PanelLoading text="模型正在回答…" />}
      {state.text && (
        <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}>{state.text}</Paragraph>
      )}
      {state.done && !state.error && (
        <Text type="secondary">
          回答由模型生成，可能出错。SQL 请自行确认后再执行 —— 执行时的只读拦截、生产确认与审计与手写 SQL 完全一致。
        </Text>
      )}
    </Drawer>
  );
}
