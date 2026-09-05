import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Collapse, Drawer, Input, Space, Spin, Tag, Typography } from 'antd';
import { CheckCircleOutlined, CheckOutlined, CopyOutlined, ImportOutlined, PlusOutlined, SendOutlined, StopOutlined } from '@ant-design/icons';
import { API } from '../constants';
import { api, apiErrorCode } from '../api';
import { authHeaders } from '../auth';
import { consumeSseBuffer, firstSqlBlock, hasUnclosedSqlFence } from '../aiSuggestion';
import {
  applyAgentEvent,
  conversationStorageKey,
  initialAgentStreamState,
  pendingQuestion,
  splitAnswerBlocks,
  splitInlineSpans,
  streamErrorMessage
} from '../aiChat';
import { checkSqlSuggestion } from '../sqlSuggestion';
import type {
  AiChatMessage,
  AiClarifyQuestion,
  AiConversation,
  AiExecutionFailure,
  AiExecutionOutcome,
  AiExecutionPlan,
  AiGroundingReport
} from '../types';

const { Paragraph, Text } = Typography;

type ToolActivity = { name: string; summary: string; error: boolean };
type PendingRequest = {
  controller: AbortController;
  messages: AiChatMessage[];
  question: string;
  requestId?: string;
};

export function AiSqlChatPanel({ open, connectionId, schemaName, currentSql, failure, outcome, plan, onFailureConsumed, onClose, onInsertSql }: {
  open: boolean;
  connectionId: number;
  schemaName?: string;
  currentSql: string;
  /**
   * 从结果区进来时带上的执行现场：跑挂的报错、跑通但结果不对的形状，或一份执行计划。
   * 面板会自动发出一轮，用户不用再敲一遍「这条为什么不对」。
   */
  failure?: AiExecutionFailure;
  outcome?: AiExecutionOutcome;
  plan?: AiExecutionPlan;
  onFailureConsumed?: () => void;
  onClose: () => void;
  onInsertSql: (sql: string, title: string) => void;
}) {
  const [messages, setMessages] = useState<AiChatMessage[]>([]);
  const [conversationId, setConversationId] = useState<string>();
  const [input, setInput] = useState('');
  const [answer, setAnswer] = useState('');
  const [grounding, setGrounding] = useState<AiGroundingReport>();
  const [liveQuestion, setLiveQuestion] = useState<AiClarifyQuestion>();
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(false);
  /**
   * 恢复短期会话这一步有没有走完。
   *
   * 单看 restoring 不够：恢复的 effect 与自动发送的 effect 在同一次提交里跑，前者调用
   * setRestoring(true) 时后者捕获到的仍是旧值 —— 结果就是抢在恢复之前先建了一段新会话，
   * 恢复完成后又把它覆盖掉。
   */
  const [restored, setRestored] = useState(false);
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
    setLiveQuestion(undefined);
    setBusy(false);
    setPhase('');
    setActivities([]);
    setError('');
    const saved = window.sessionStorage.getItem(storageKey);
    if (!saved) {
      setRestored(true);
      return;
    }
    let active = true;
    setRestored(false);
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
    }).finally(() => {
      if (!active) return;
      setRestoring(false);
      setRestored(true);
    });
    return () => { active = false; };
  }, [connectionId, schemaName, storageKey]);

  useEffect(() => () => stopCurrent(false), []);

  // 从结果区点进来时不用再让用户敲一遍「这条为什么不对」：等会话恢复完就自动发一轮。
  useEffect(() => {
    if (!open || busy || !restored) return;
    if (failure) {
      onFailureConsumed?.();
      void send({ question: '这条 SQL 执行失败了，请找出原因并给出修正后的 SQL。', failure });
      return;
    }
    if (outcome) {
      onFailureConsumed?.();
      void send({ question: '这条 SQL 跑通了，但结果看起来不对。请判断写法哪里与需求不符，并给出修正后的 SQL。', outcome });
      return;
    }
    if (plan) {
      onFailureConsumed?.();
      void send({ question: '这条查询的执行计划如下，请解释它为什么慢，并给出可行的改法。', plan });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, failure, outcome, plan, restored]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, answer, phase, activities, grounding]);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1_600);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const question = pendingQuestion(messages, liveQuestion);
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

  async function send(preset?: {
    question: string;
    failure?: AiExecutionFailure;
    outcome?: AiExecutionOutcome;
    plan?: AiExecutionPlan;
  }) {
    const question = preset ? preset.question : input.trim();
    const failedExecution = preset?.failure;
    const reviewedExecution = preset?.outcome;
    const explainedPlan = preset?.plan;
    if (!question || busy || restoring) return;
    const previousMessages = messages;
    setMessages([...messages, { role: 'USER', text: question }]);
    if (!preset) setInput('');
    setAnswer('');
    setGrounding(undefined);
    setLiveQuestion(undefined);
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
          // 带着失败现场时不再附带编辑器草稿：要诊断的是跑挂的那一条。
          currentSql: !preset && !conversationId && previousMessages.length === 0
            ? currentSql.trim() || undefined
            : undefined,
          failure: failedExecution,
          outcome: reviewedExecution,
          plan: explainedPlan
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
      // 这一轮以反问收尾：没有 SQL 是对的，不该按「模型什么都没返回」处理。
      if (stream.question) {
        setLiveQuestion(stream.question);
        setMessages((current) => [...current, {
          role: 'ASSISTANT', text: stream.question!.question, question: stream.question
        }]);
      } else {
        if (!nextAnswer.trim()) throw new Error('模型没有返回 SQL 或说明。');
        setMessages((current) => [...current, { role: 'ASSISTANT', text: nextAnswer, grounding: nextGrounding }]);
      }
      setAnswer('');
      setGrounding(undefined);
      setPhase('');
    } catch (cause) {
      if (!controller.signal.aborted && !(cause instanceof DOMException && cause.name === 'AbortError')) {
        setMessages(previousMessages);
        // 自动发起的那一轮失败时不要把生成的问句塞进输入框，用户没打过这句话。
        if (!preset) setInput(question);
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
            <Text strong>用业务语言描述你要查询的内容，或把执行报错交给它分析</Text>
            <Text type="secondary">AI 会搜索表、字段与业务词典，沿外键检查关联，并在目标数据库编译校验后给出 SQL。</Text>
          </div>
        )}
        {messages.map((message, index) => (
          <div key={`${message.role}-${index}`} className={`ai-chat-message is-${message.role.toLowerCase()}`}>
            <Text type="secondary">{message.role === 'USER' ? '你' : 'AI'}</Text>
            <AnswerBody text={message.text} />
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
            <AnswerBody text={answer} />
            {grounding && <Grounding report={grounding} />}
          </div>
        )}
        {question && !busy && (
          <ClarifyPrompt question={question} onPick={(label) => void send({ question: label })} />
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

/**
 * 模型的反问。
 *
 * <p>选项画成按钮而不是让用户照着问句手打：问题本来就是模型提出来的，答案的措辞也该由它定 ——
 * 用户重新组织一遍语言，反而可能给出一个它没预期的说法，又要多问一轮。没有选项时只显示问题，
 * 用户在下面的输入框里回答。</p>
 */
function ClarifyPrompt({ question, onPick }: {
  question: AiClarifyQuestion;
  onPick: (label: string) => void;
}) {
  return (
    <div className="ai-chat-clarify">
      <Text strong>{question.question}</Text>
      {question.options.length > 0 && (
        <Space wrap size={6}>
          {question.options.map((option) => (
            <Button key={option.label} size="small" onClick={() => onPick(option.label)}
              title={option.detail || undefined}>
              {option.label}
            </Button>
          ))}
        </Space>
      )}
    </div>
  );
}

/**
 * 回答正文：说明按段落走，```sql 块按等宽代码渲染。
 *
 * 之前整段当纯文本渲染，反引号原样显示、SQL 挤在正文字体里 —— 这一屏最该看清的东西反而最难读。
 */
function AnswerBody({ text }: { text: string }) {
  const blocks = useMemo(() => splitAnswerBlocks(text), [text]);
  return (
    <>
      {blocks.map((block, index) => (block.kind === 'code'
        ? <pre key={index} className="ai-chat-code"><code>{block.code}</code></pre>
        : <Paragraph key={index}><Inline text={block.text} /></Paragraph>))}
    </>
  );
}

function Inline({ text }: { text: string }) {
  return (
    <>
      {splitInlineSpans(text).map((span, index) => {
        if (span.kind === 'strong') return <strong key={index}>{span.text}</strong>;
        if (span.kind === 'code') return <code key={index} className="ai-chat-inline-code">{span.text}</code>;
        return <span key={index}>{span.text}</span>;
      })}
    </>
  );
}

function Grounding({ report }: { report: AiGroundingReport }) {
  const labels = { TABLE: '表', COLUMN: '字段', FOREIGN_KEY: '外键', QUERY_HISTORY: '历史写法' } as const;
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

