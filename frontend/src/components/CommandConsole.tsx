import { memo, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Button, Space, Tag, Tooltip, Typography, message } from 'antd';
import { CloseOutlined, CopyOutlined, StopOutlined } from '@ant-design/icons';
import { api } from '../api';
import { useVisiblePolling } from '../hooks/useVisiblePolling';
import { commandLogText, commandProgressText, isTerminalJob, lastSeq, mergeCommandLog } from '../commandWindow';
import type { SqlCommandLogEntry, SqlCommandView } from '../types';
import { localizeError } from '../utils';

const { Text } = Typography;

/**
 * 命令窗口：一次后台运行的滚动日志，像 SQL*Plus 那样一条语句跟一句结果。
 *
 * <p>按 seq 增量拉日志，跑着的时候每 0.8 秒一次；结束后把剩下的拉完就停。停在底部时新日志自动滚到
 * 视野里，往上翻看时不打扰。点语句或报错行会在编辑器里选中那几行。</p>
 */
export const CommandConsole = memo(function CommandConsole({ jobId, onLocate, onClose, onFinished }: {
  jobId: number;
  onLocate: (startLine: number, endLine: number) => void;
  onClose: () => void;
  /** 结束时调一次（刷新资源树等）。 */
  onFinished?: (view: SqlCommandView) => void;
}) {
  const [toast, contextHolder] = message.useMessage();
  const [entries, setEntries] = useState<SqlCommandLogEntry[]>([]);
  const [dropped, setDropped] = useState(0);
  const [view, setView] = useState<SqlCommandView>();
  const [error, setError] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const entriesRef = useRef<SqlCommandLogEntry[]>([]);
  const doneRef = useRef(false);
  const notifiedRef = useRef(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);

  useEffect(() => {
    entriesRef.current = [];
    doneRef.current = false;
    notifiedRef.current = false;
    setEntries([]);
    setDropped(0);
    setView(undefined);
    setError('');
  }, [jobId]);

  const poll = useCallback(async () => {
    if (doneRef.current) return;
    try {
      // 一次轮询内把积压的拉完（hasMore），而不是每 0.8 秒只拉一页。
      for (let page = 0; page < 10; page++) {
        const next = await api<SqlCommandView>(`/sql-file-executions/${jobId}/command-log?after=${lastSeq(entriesRef.current)}&limit=500`);
        const merged = mergeCommandLog(entriesRef.current, next.entries);
        entriesRef.current = merged.entries;
        if (merged.dropped) setDropped((value) => value + merged.dropped);
        setEntries(merged.entries);
        setView(next);
        setError('');
        if (!next.hasMore) {
          if (isTerminalJob(next.job)) {
            doneRef.current = true;
            if (!notifiedRef.current) {
              notifiedRef.current = true;
              onFinished?.(next);
            }
          }
          break;
        }
      }
    } catch (failure) {
      setError(localizeError(failure));
    }
  }, [jobId, onFinished]);

  useVisiblePolling({ enabled: !isTerminalJob(view?.job) || !doneRef.current, intervalMs: 800, task: poll, resetKey: jobId, immediate: true });

  useLayoutEffect(() => {
    const node = scrollRef.current;
    if (node && stickRef.current) node.scrollTop = node.scrollHeight;
  }, [entries]);

  const cancel = async () => {
    setCancelling(true);
    try {
      await api(`/sql-file-executions/${jobId}/cancel`, { method: 'POST' });
      void poll();
    } catch (failure) {
      toast.error(localizeError(failure));
    } finally {
      setCancelling(false);
    }
  };

  const copyAll = async () => {
    try {
      await navigator.clipboard.writeText(commandLogText(entriesRef.current));
      toast.success('已复制日志');
    } catch {
      toast.error('复制失败，请手动选择内容');
    }
  };

  const job = view?.job;
  const failedCount = view?.command.failedCount ?? 0;
  const terminal = isTerminalJob(job);
  const statusColor = !job ? 'processing' : job.status === 'SUCCESS' ? (failedCount > 0 ? 'warning' : 'success')
    : job.status === 'FAILED' ? 'error' : job.status === 'CANCELLED' ? 'default' : 'processing';
  return (
    <div className="command-console">
      {contextHolder}
      <div className="command-console-header">
        <Space size={8} wrap>
          <Text strong>命令窗口</Text>
          <Tag color={statusColor}>{commandProgressText(job, failedCount)}</Tag>
          {view?.command.logTruncated && <Text type="secondary">日志过长，只保留了错误</Text>}
          {error && <Text type="danger">{error}</Text>}
        </Space>
        <Space size={4}>
          {!terminal && (
            <Button size="small" danger icon={<StopOutlined />} loading={cancelling} onClick={() => void cancel()}>停止</Button>
          )}
          <Tooltip title="复制全部日志">
            <Button size="small" type="text" icon={<CopyOutlined />} aria-label="复制全部日志" onClick={() => void copyAll()} />
          </Tooltip>
          <Tooltip title={terminal ? '关闭命令窗口' : '关闭（后台仍在执行，可在 SQL 文件任务里查看）'}>
            <Button size="small" type="text" icon={<CloseOutlined />} aria-label="关闭命令窗口" onClick={onClose} />
          </Tooltip>
        </Space>
      </div>
      <div
        ref={scrollRef}
        className="command-console-body"
        role="log"
        aria-live="polite"
        onScroll={(event) => {
          const node = event.currentTarget;
          stickRef.current = node.scrollHeight - node.scrollTop - node.clientHeight < 24;
        }}
      >
        {dropped > 0 && <div className="command-line command-line-info">-- 更早的 {dropped} 行已不在这里显示，可复制或在 SQL 文件任务里查看结果</div>}
        {entries.map((entry) => <CommandLine key={entry.seq} entry={entry} onLocate={onLocate} />)}
        {!terminal && <div className="command-line command-line-info command-cursor">…</div>}
      </div>
    </div>
  );
});

const CommandLine = memo(function CommandLine({ entry, onLocate }: { entry: SqlCommandLogEntry; onLocate: (start: number, end: number) => void }) {
  const locatable = entry.startLine != null && (entry.kind === 'STATEMENT' || entry.kind === 'ERROR' || entry.kind === 'COMPILE');
  const locate = locatable ? () => onLocate(entry.startLine!, entry.endLine ?? entry.startLine!) : undefined;
  const lines = entry.startLine ? `第 ${entry.startLine}${entry.endLine && entry.endLine !== entry.startLine ? `–${entry.endLine}` : ''} 行` : '';
  switch (entry.kind) {
    case 'STATEMENT':
      return (
        <div className="command-line command-line-statement" role="button" tabIndex={0} title={`${lines}，点击在编辑器中选中`}
          onClick={locate} onKeyDown={(event) => { if (event.key === 'Enter') locate?.(); }}>
          <span className="command-prompt">SQL&gt;</span> {entry.text}
        </div>
      );
    case 'ERROR':
      return (
        <div className="command-line command-line-error" role="button" tabIndex={0} title="点击在编辑器中选中出错的语句"
          onClick={locate} onKeyDown={(event) => { if (event.key === 'Enter') locate?.(); }}>
          错误{lines ? `（${lines}）` : ''}：{entry.text}{entry.elapsedMs != null ? <span className="command-elapsed"> {entry.elapsedMs}ms</span> : null}
        </div>
      );
    case 'RESULT':
      return <div className="command-line command-line-result">{entry.text}{entry.elapsedMs != null ? <span className="command-elapsed"> {entry.elapsedMs}ms</span> : null}</div>;
    case 'OUTPUT':
      return <div className="command-line command-line-output">{entry.text}</div>;
    case 'PROMPT':
      return <div className="command-line command-line-prompt">{entry.text}</div>;
    case 'COMPILE':
      return <div className="command-line command-line-compile" onClick={locate}>{entry.text}</div>;
    default:
      return <div className="command-line command-line-info">-- {entry.text}</div>;
  }
});
