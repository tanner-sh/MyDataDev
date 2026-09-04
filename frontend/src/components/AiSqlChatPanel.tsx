import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Drawer, Input, Space, Tag, Typography } from 'antd';
import { CheckOutlined, CopyOutlined, ImportOutlined, PlusOutlined, SendOutlined, StopOutlined } from '@ant-design/icons';
import { API } from '../constants';
import { authHeaders } from '../auth';
import { consumeSseBuffer, firstSqlBlock, hasUnclosedSqlFence } from '../aiSuggestion';
import { checkSqlSuggestion } from '../sqlSuggestion';

const { Paragraph, Text } = Typography;

type ChatMessage = { role: 'USER' | 'ASSISTANT'; text: string };
type ToolActivity = { name: string; summary: string; error: boolean };

export function AiSqlChatPanel({ open, connectionId, schemaName, currentSql, onClose, onInsertSql }: {
  open: boolean;
  connectionId: number;
  schemaName?: string;
  currentSql: string;
  onClose: () => void;
  onInsertSql: (sql: string, title: string) => void;
}) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [phase, setPhase] = useState('');
  const [activities, setActivities] = useState<ToolActivity[]>([]);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const abortRef = useRef<AbortController | undefined>(undefined);
  const pendingRef = useRef<{ controller: AbortController; messages: ChatMessage[]; question: string } | undefined>(undefined);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    pendingRef.current = undefined;
    abortRef.current?.abort();
    abortRef.current = undefined;
    setMessages([]);
    setInput('');
    setAnswer('');
    setBusy(false);
    setPhase('');
    setActivities([]);
    setError('');
  }, [connectionId, schemaName]);

  useEffect(() => () => abortRef.current?.abort(), []);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, answer, phase, activities]);

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

  function reset() {
    pendingRef.current = undefined;
    abortRef.current?.abort();
    abortRef.current = undefined;
    setMessages([]);
    setInput('');
    setAnswer('');
    setBusy(false);
    setPhase('');
    setActivities([]);
    setError('');
  }

  async function send() {
    const question = input.trim();
    if (!question || busy) return;
    const nextMessages: ChatMessage[] = [...messages, { role: 'USER', text: question }];
    setMessages(nextMessages);
    setInput('');
    setAnswer('');
    setActivities([]);
    setError('');
    setPhase('正在理解需求并检查数据库结构…');
    setBusy(true);
    const controller = new AbortController();
    abortRef.current = controller;
    pendingRef.current = { controller, messages, question };
    let nextAnswer = '';
    try {
      const response = await fetch(`${API}/ai/sql/chat/stream`, {
        method: 'POST',
        credentials: 'include',
        signal: controller.signal,
        headers: { 'Content-Type': 'application/json', 'X-User': 'admin', ...authHeaders('POST') },
        body: JSON.stringify({
          connectionId,
          schemaName,
          messages: nextMessages,
          currentSql: messages.length === 0 ? currentSql.trim() || undefined : undefined
        })
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
        throw new Error(message);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parsed = consumeSseBuffer(buffer);
        buffer = parsed.rest;
        for (const event of parsed.events) {
          if (event.event === 'delta' && typeof event.data.text === 'string') {
            nextAnswer += event.data.text;
            setAnswer(nextAnswer);
          } else if (event.event === 'phase' && typeof event.data.text === 'string') {
            setPhase(event.data.text);
          } else if (event.event === 'tool') {
            setActivities((current) => [...current, {
              name: typeof event.data.name === 'string' ? event.data.name : 'metadata',
              summary: typeof event.data.summary === 'string' ? event.data.summary : '已检查数据库结构',
              error: event.data.error === true
            }]);
          } else if (event.event === 'failed') {
            throw new Error(typeof event.data.message === 'string' ? event.data.message : 'AI 调用失败');
          }
        }
      }
      if (!nextAnswer.trim()) throw new Error('模型没有返回 SQL 或说明。');
      setMessages((current) => [...current, { role: 'ASSISTANT', text: nextAnswer }]);
      setAnswer('');
      setPhase('');
    } catch (cause) {
      if (!controller.signal.aborted) {
        setMessages(messages);
        setInput(question);
        setAnswer('');
        setPhase('');
        setError(cause instanceof Error ? cause.message : 'AI 调用失败');
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = undefined;
        pendingRef.current = undefined;
        setBusy(false);
      }
    }
  }

  return (
    <Drawer
      open={open}
      title="AI SQL 助手"
      width={600}
      onClose={() => { pendingRef.current = undefined; abortRef.current?.abort(); onClose(); }}
      extra={(
        <Space size={4}>
          <Button size="small" icon={<PlusOutlined />} disabled={busy || messages.length === 0} onClick={reset}>新对话</Button>
          {busy && (
            <Button size="small" danger icon={<StopOutlined />} onClick={() => {
              const pending = pendingRef.current;
              pendingRef.current = undefined;
              abortRef.current = undefined;
              pending?.controller.abort();
              if (pending) {
                setMessages(pending.messages);
                setInput(pending.question);
              }
              setAnswer('');
              setBusy(false);
              setPhase('');
            }}>
              停止
            </Button>
          )}
        </Space>
      )}
      styles={{ body: { padding: 0, display: 'flex', flexDirection: 'column', height: '100%' } }}
    >
      <div className="ai-chat-context">
        <Tag color="blue">只读元数据工具</Tag>
        <Text type="secondary">{schemaName || '连接默认命名空间'} · AI 可查表、字段、注释和外键，但不会执行 SQL</Text>
      </div>
      <div ref={scrollRef} className="ai-chat-messages">
        {messages.length === 0 && !answer && (
          <div className="ai-chat-empty">
            <Text strong>用业务语言描述你要查询的内容</Text>
            <Text type="secondary">AI 会自己搜索表和字段注释、读取关联关系，再生成一条 SQL。之后可以继续发送消息修正。</Text>
          </div>
        )}
        {messages.map((message, index) => (
          <div key={`${message.role}-${index}`} className={`ai-chat-message is-${message.role.toLowerCase()}`}>
            <Text type="secondary">{message.role === 'USER' ? '你' : 'AI'}</Text>
            <Paragraph>{message.text}</Paragraph>
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
          </div>
        )}
        {error && <Alert type="error" showIcon message="AI 调用失败" description={error} />}
      </div>
      <div className="ai-chat-composer">
        {check?.warning && <Alert type="warning" showIcon message={check.warning} />}
        {sqlReady && sql && (
          <Space wrap>
            <Button
              size="small"
              type="primary"
              icon={<ImportOutlined />}
              onClick={() => onInsertSql(sql, firstQuestion.trim().slice(0, 40) || 'AI 生成的 SQL')}
            >
              写入新标签页
            </Button>
            <Button
              size="small"
              icon={copied ? <CheckOutlined /> : <CopyOutlined />}
              onClick={() => { void navigator.clipboard?.writeText(sql).then(() => setCopied(true)).catch(() => undefined); }}
            >
              {copied ? '已复制' : '复制 SQL'}
            </Button>
          </Space>
        )}
        <Input.TextArea
          autoFocus
          rows={3}
          value={input}
          disabled={busy}
          placeholder={messages.length === 0
            ? '例如：查询最近一周登录过的用户名称和所属角色'
            : '继续修正，例如：只要启用用户，再加上最后登录时间'}
          onChange={(event) => setInput(event.target.value)}
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              void send();
            }
          }}
        />
        <div className="ai-chat-send-row">
          <Text type="secondary">Enter 发送，Shift+Enter 换行</Text>
          <Button type="primary" icon={<SendOutlined />} loading={busy} disabled={!input.trim()} onClick={() => void send()}>
            {messages.length === 0 ? '生成 SQL' : '继续修正'}
          </Button>
        </div>
      </div>
    </Drawer>
  );
}
