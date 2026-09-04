import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Collapse, Drawer, Input, Space, Spin, Tag, Typography } from 'antd';
import { CheckCircleOutlined, CheckOutlined, CopyOutlined, ImportOutlined, PlusOutlined, SendOutlined, StopOutlined } from '@ant-design/icons';
import { API } from '../constants';
import { api, apiErrorCode } from '../api';
import { authHeaders } from '../auth';
import { consumeSseBuffer, firstSqlBlock, hasUnclosedSqlFence } from '../aiSuggestion';
import { applyAgentEvent, conversationStorageKey, initialAgentStreamState, streamErrorMessage } from '../aiChat';
import { checkSqlSuggestion } from '../sqlSuggestion';
import type { AiChatMessage, AiConversation, AiGroundingReport } from '../types';

const { Paragraph, Text } = Typography;

type ToolActivity = { name: string; summary: string; error: boolean };
type PendingRequest = {
  controller: AbortController;
  messages: AiChatMessage[];
  question: string;
  requestId?: string;
};

export function AiSqlChatPanel({ open, connectionId, schemaName, currentSql, onClose, onInsertSql }: {
  open: boolean;
  connectionId: number;
  schemaName?: string;
  currentSql: string;
  onClose: () => void;
  onInsertSql: (sql: string, title: string) => void;
}) {
  const [messages, setMessages] = useState<AiChatMessage[]>([]);
  const [conversationId, setConversationId] = useState<string>();
  const [input, setInput] = useState('');
  const [answer, setAnswer] = useState('');
  const [grounding, setGrounding] = useState<AiGroundingReport>();
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [phase, setPhase] = useState('');
  const [activities, setActivities] = useState<ToolActivity[]>([]);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const pendingRef = useRef<PendingRequest | undefined>(undefined);
  const scrollRef = useRef<HTMLDivElement>(null);
  const storageKey = conversationStorageKey(connectionId, schemaName);

  useEffect(() => {
    stopCurrent(false);
    setMessages([]);
    setConversationId(undefined);
    setInput('');
    setAnswer('');
    setGrounding(undefined);
    setBusy(false);
    setPhase('');
    setActivities([]);
    setError('');
    const saved = window.sessionStorage.getItem(storageKey);
    if (!saved) return;
    let active = true;
    setRestoring(true);
    const query = new URLSearchParams({ connectionId: String(connectionId) });
    if (schemaName) query.set('schemaName', schemaName);
    void api<AiConversation>(`/ai/sql/conversations/${saved}?${query}`).then((conversation) => {
      if (!active) return;
      setConversationId(conversation.id);
      setMessages(conversation.messages);
    }).catch((cause) => {
      window.sessionStorage.removeItem(storageKey);
      if (active && apiErrorCode(cause) !== 'AI_CONVERSATION_EXPIRED') {
        setError(cause instanceof Error ? cause.message : 'AI 对话恢复失败');
      }
    }).finally(() => { if (active) setRestoring(false); });
    return () => { active = false; };
  }, [connectionId, schemaName, storageKey]);

  useEffect(() => () => stopCurrent(false), []);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, answer, phase, activities, grounding]);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1_600);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const visibleAnswer = answer || [...messages].reverse().find((message) => message.role === 'ASSISTANT')?.text || '';
  const sql = firstSqlBlock(visibleAnswer);
  const sqlReady = Boolean(sql) && !hasUnclosedSqlFence(visibleAnswer);
  const check = useMemo(() => (sqlReady && sql ? checkSqlSuggestion(sql) : undefined), [sql, sqlReady]);
  const firstQuestion = messages.find((message) => message.role === 'USER')?.text || input;

  function stopCurrent(restoreQuestion: boolean) {
    const pending = pendingRef.current;
    pendingRef.current = undefined;
    if (!pending) return;
    if (pending.requestId) void api(`/ai/sql/chat/${pending.requestId}/cancel`, { method: 'POST' }).catch(() => undefined);
    pending.controller.abort();
    if (restoreQuestion) {
      setMessages(pending.messages);
      setInput(pending.question);
      setAnswer('');
      setGrounding(undefined);
      setBusy(false);
      setPhase('');
    }
  }

  function reset() {
    stopCurrent(false);
    if (conversationId) {
      void api(`/ai/sql/conversations/${conversationId}?connectionId=${connectionId}`, { method: 'DELETE' }).catch(() => undefined);
    }
    window.sessionStorage.removeItem(storageKey);
    setConversationId(undefined);
    setMessages([]);
    setInput('');
    setAnswer('');
    setGrounding(undefined);
    setBusy(false);
    setPhase('');
    setActivities([]);
    setError('');
  }

  async function send() {
    const question = input.trim();
    if (!question || busy || restoring) return;
    const previousMessages = messages;
    setMessages([...messages, { role: 'USER', text: question }]);
    setInput('');
    setAnswer('');
    setGrounding(undefined);
    setActivities([]);
    setError('');
    setPhase('正在理解需求并检查数据库结构…');
    setBusy(true);
    const controller = new AbortController();
    const pending: PendingRequest = { controller, messages: previousMessages, question };
    pendingRef.current = pending;
    let nextAnswer = '';
    let nextGrounding: AiGroundingReport | undefined;
    try {
      const response = await fetch(`${API}/ai/sql/chat/stream`, {
        method: 'POST',
        credentials: 'include',
        signal: controller.signal,
        headers: { 'Content-Type': 'application/json', 'X-User': 'admin', ...authHeaders('POST') },
        body: JSON.stringify({
          connectionId,
          schemaName,
          conversationId,
          message: question,
          currentSql: !conversationId && previousMessages.length === 0 ? currentSql.trim() || undefined : undefined
        })
      });
      if (!response.ok || !response.body) {
        throw new Error(streamErrorMessage(await response.text(), response.statusText));
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let stream = initialAgentStreamState('正在理解需求并检查数据库结构…');
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parsed = consumeSseBuffer(buffer);
        buffer = parsed.rest;
        for (const event of parsed.events) {
          const previous = stream;
          stream = applyAgentEvent(stream, event);
          if (stream.failure) throw new Error(stream.failure);
          if (stream.cancelled) throw new DOMException('AI 请求已取消', 'AbortError');
          if (stream.conversationId && stream.conversationId !== previous.conversationId) {
            setConversationId(stream.conversationId);
            window.sessionStorage.setItem(storageKey, stream.conversationId);
          }
          pending.requestId = stream.requestId;
        }
        setAnswer(stream.answer);
        setPhase(stream.phase);
        setActivities(stream.activities);
        setGrounding(stream.grounding);
      }
      nextAnswer = stream.answer;
      nextGrounding = stream.grounding;
      if (!nextAnswer.trim()) throw new Error('模型没有返回 SQL 或说明。');
      setMessages((current) => [...current, { role: 'ASSISTANT', text: nextAnswer, grounding: nextGrounding }]);
      setAnswer('');
      setGrounding(undefined);
      setPhase('');
    } catch (cause) {
      if (!controller.signal.aborted && !(cause instanceof DOMException && cause.name === 'AbortError')) {
        setMessages(previousMessages);
        setInput(question);
        setAnswer('');
        setGrounding(undefined);
        setPhase('');
        setError(cause instanceof Error ? cause.message : 'AI 调用失败');
      }
    } finally {
      if (pendingRef.current === pending) {
        pendingRef.current = undefined;
        setBusy(false);
      }
    }
  }

  return (
    <Drawer
      open={open}
      title="AI SQL 助手"
      width={640}
      onClose={() => { stopCurrent(false); onClose(); }}
      extra={(
        <Space size={4}>
          <Button size="small" icon={<PlusOutlined />} disabled={busy || messages.length === 0} onClick={reset}>新对话</Button>
          {busy && <Button size="small" danger icon={<StopOutlined />} onClick={() => stopCurrent(true)}>停止</Button>}
        </Space>
      )}
      styles={{ body: { padding: 0, display: 'flex', flexDirection: 'column', height: '100%' } }}
    >
      <div className="ai-chat-context">
        <Tag color="blue">只读 Agent</Tag>
        <Tag color="green">SQL 编译校验</Tag>
        <Text type="secondary">{schemaName || '连接默认命名空间'} · 可查结构、业务词典和外键，不执行查询</Text>
      </div>
      <div ref={scrollRef} className="ai-chat-messages">
        {restoring && <Spin tip="正在恢复短期对话…" />}
        {!restoring && messages.length === 0 && !answer && (
          <div className="ai-chat-empty">
            <Text strong>用业务语言描述你要查询的内容</Text>
            <Text type="secondary">AI 会搜索表、字段与业务词典，沿外键检查关联，并在目标数据库编译校验后给出 SQL。</Text>
          </div>
        )}
        {messages.map((message, index) => (
          <div key={`${message.role}-${index}`} className={`ai-chat-message is-${message.role.toLowerCase()}`}>
            <Text type="secondary">{message.role === 'USER' ? '你' : 'AI'}</Text>
            <Paragraph>{message.text}</Paragraph>
            {message.role === 'ASSISTANT' && message.grounding && <Grounding report={message.grounding} />}
          </div>
        ))}
        {activities.length > 0 && (
          <div className="ai-chat-tools">
            {activities.map((activity, index) => (
              <Text key={`${activity.name}-${index}`} type={activity.error ? 'danger' : 'secondary'}>
                {activity.error ? '×' : '✓'} {activity.summary}
              </Text>
            ))}
          </div>
        )}
        {phase && <Text type="secondary" className="ai-chat-phase">{phase}</Text>}
        {answer && (
          <div className="ai-chat-message is-assistant">
            <Text type="secondary">AI</Text>
            <Paragraph>{answer}</Paragraph>
            {grounding && <Grounding report={grounding} />}
          </div>
        )}
        {error && <Alert type="error" showIcon message="AI 调用失败" description={error} />}
      </div>
      <div className="ai-chat-composer">
        {check?.warning && <Alert type="warning" showIcon message={check.warning} />}
        {sqlReady && sql && (
          <Space wrap>
            <Button size="small" type="primary" icon={<ImportOutlined />}
              onClick={() => onInsertSql(sql, firstQuestion.trim().slice(0, 40) || 'AI 生成的 SQL')}>
              写入新标签页
            </Button>
            <Button size="small" icon={copied ? <CheckOutlined /> : <CopyOutlined />}
              onClick={() => { void navigator.clipboard?.writeText(sql).then(() => setCopied(true)).catch(() => undefined); }}>
              {copied ? '已复制' : '复制 SQL'}
            </Button>
          </Space>
        )}
        <Input.TextArea
          autoFocus rows={3} value={input} disabled={busy || restoring}
          placeholder={messages.length === 0
            ? '例如：查询最近一周登录过的用户名称和所属角色'
            : '继续修正，例如：只要启用用户，再加上最后登录时间'}
          onChange={(event) => setInput(event.target.value)}
          onPressEnter={(event) => { if (!event.shiftKey) { event.preventDefault(); void send(); } }}
        />
        <div className="ai-chat-send-row">
          <Text type="secondary">短期会话会在本页刷新后恢复 · Enter 发送</Text>
          <Button type="primary" icon={<SendOutlined />} loading={busy} disabled={!input.trim() || restoring} onClick={() => void send()}>
            {messages.length === 0 ? '生成 SQL' : '继续修正'}
          </Button>
        </div>
      </div>
    </Drawer>
  );
}

function Grounding({ report }: { report: AiGroundingReport }) {
  const labels = { TABLE: '表', COLUMN: '字段', FOREIGN_KEY: '外键' } as const;
  return (
    <Collapse
      size="small"
      className="ai-chat-grounding"
      items={[{
        key: 'grounding',
        label: <Space size={6}><CheckCircleOutlined />结构依据 · {report.references.length} 项</Space>,
        children: (
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            <Text type={report.validated ? 'success' : 'secondary'}>{report.validationMessage}</Text>
            {report.references.map((reference) => (
              <div key={`${reference.kind}-${reference.label}`} className="ai-chat-grounding-item">
                <Tag>{labels[reference.kind]}</Tag>
                <Text code>{reference.label}</Text>
                {reference.detail && <Text type="secondary">{reference.detail}</Text>}
              </div>
            ))}
          </Space>
        )
      }]}
    />
  );
}

