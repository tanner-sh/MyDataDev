import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PanelEmpty } from './PanelState';
import { Button, DatePicker, Input, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { DownloadOutlined, LeftOutlined, ReloadOutlined, RightOutlined } from '@ant-design/icons';
import { api, downloadBlob } from '../api';
import { API } from '../constants';
import type { AuditEvent, AuditEventPage, AuditFacets, Connection } from '../types';
import { formatHistoryTime, localizeError } from '../utils';
import {
  auditActionColor,
  auditActionLabel,
  auditPageSummary,
  auditRequestParams,
  auditTargetLabel,
  AUDIT_SEARCH_DEBOUNCE_MS,
  INITIAL_AUDIT_QUERY,
  isDangerousAuditAction,
  parseAuditConnectionId,
  type AuditQuery
} from '../auditLog';

const { Text } = Typography;

/**
 * 审计日志面板。
 *
 * 原来自带 Drawer 外壳，现在只渲染内容 —— 管理类面板统一嵌在一个带左侧导航的抽屉里，
 * 各面板自己再套一层抽屉的话，用户从「备份」跳到「审计」就得先关再开。
 */
export const AuditLogPanel = memo(function AuditLogPanel({ open, connections }: {
  /** 面板是否可见。不可见时不取数，切回来会重新拉一次。 */
  open: boolean;
  connections: Connection[];
}) {
  const [query, setQuery] = useState<AuditQuery>(INITIAL_AUDIT_QUERY);
  const [keywordDraft, setKeywordDraft] = useState('');
  const [page, setPage] = useState<AuditEventPage | null>(null);
  const [facets, setFacets] = useState<AuditFacets>({ actors: [], actions: [] });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [fullDetail, setFullDetail] = useState<Record<number, string>>({});
  const requestSeqRef = useRef(0);
  const debounceRef = useRef<number | null>(null);

  const connectionNames = useMemo(
    () => new Map(connections.map((connection) => [connection.id, connection.name])),
    [connections]
  );

  const load = useCallback(async (next: AuditQuery) => {
    const requestId = ++requestSeqRef.current;
    setLoading(true);
    setError('');
    try {
      const result = await api<AuditEventPage>(`/audit?${auditRequestParams(next)}`);
      if (requestId !== requestSeqRef.current) return;
      setPage(result);
      setQuery(next);
    } catch (e) {
      if (requestId !== requestSeqRef.current) return;
      setError(localizeError(e));
    } finally {
      if (requestId === requestSeqRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!open) return;
    setKeywordDraft('');
    setExpandedId(null);
    void load(INITIAL_AUDIT_QUERY);
    api<AuditFacets>('/audit/facets').then(setFacets).catch(() => undefined);
  }, [load, open]);

  useEffect(() => () => {
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
  }, []);

  function queueKeyword(value: string) {
    setKeywordDraft(value);
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null;
      void load({ ...query, keyword: value, page: 0 });
    }, AUDIT_SEARCH_DEBOUNCE_MS);
  }

  async function toggleDetail(event: AuditEvent) {
    if (expandedId === event.id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(event.id);
    if (!event.detailTruncated || fullDetail[event.id]) return;
    try {
      const result = await api<{ detail: string }>(`/audit/${event.id}/detail`);
      setFullDetail((current) => ({ ...current, [event.id]: result.detail }));
    } catch {
      // 展开失败时仍显示列表里截断的那段，不打断浏览。
    }
  }

  async function exportAll() {
    try {
      const response = await fetch(`${API}/audit/export?${auditRequestParams({ ...query, page: 0 })}`, { credentials: 'include' });
      if (!response.ok) throw new Error((await response.json().catch(() => ({})) as { message?: string }).message || '导出失败');
      downloadBlob(await response.blob(), `audit-${Date.now()}.csv`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '导出审计日志失败');
    }
  }

  const items = page?.items || [];

  return (
    <div className="management-section">
      <header className="management-section-header">
        <Text strong>审计日志</Text>
        <Space size={4}>
          <Tooltip title="按当前筛选导出，最多 10000 条">
            <Button size="small" icon={<DownloadOutlined />} disabled={items.length === 0} onClick={() => void exportAll()}>导出筛选结果</Button>
          </Tooltip>
          <Tooltip title="刷新">
            <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={() => void load(query)} />
          </Tooltip>
        </Space>
      </header>
      <div className="audit-filters">
        <Input.Search
          allowClear
          className="audit-keyword"
          placeholder="搜索目标或详情（例如表名、SQL 片段）"
          value={keywordDraft}
          loading={loading}
          onChange={(event) => queueKeyword(event.target.value)}
          onSearch={(value) => void load({ ...query, keyword: value, page: 0 })}
        />
        <Space size={6} wrap>
          <Select
            allowClear
            size="small"
            className="audit-filter-select"
            placeholder="全部操作者"
            value={query.actor}
            options={facets.actors.map((actor) => ({ value: actor, label: actor }))}
            onChange={(actor) => void load({ ...query, actor, page: 0 })}
          />
          <Select
            allowClear
            showSearch
            size="small"
            className="audit-filter-select audit-action-select"
            placeholder="全部动作"
            value={query.action}
            optionFilterProp="label"
            options={facets.actions.map((action) => ({ value: action, label: auditActionLabel(action) }))}
            onChange={(action) => void load({ ...query, action, page: 0 })}
          />
          <Select
            allowClear
            showSearch
            size="small"
            className="audit-filter-select"
            placeholder="全部连接"
            value={query.connectionId}
            optionFilterProp="label"
            options={connections.map((connection) => ({ value: connection.id, label: connection.name }))}
            onChange={(connectionId) => void load({ ...query, connectionId, page: 0 })}
          />
          <DatePicker.RangePicker
            size="small"
            showTime
            placeholder={['起始时间', '结束时间']}
            onChange={(range) => void load({
              ...query,
              from: range?.[0]?.toDate().toISOString(),
              to: range?.[1]?.toDate().toISOString(),
              page: 0
            })}
          />
        </Space>
      </div>

      {error && <Text type="danger" className="audit-error">{error}</Text>}

      {loading && items.length === 0 ? (
        <div className="audit-loading"><Spin size="small" /> <Text type="secondary">正在加载审计记录…</Text></div>
      ) : items.length === 0 ? (
        <PanelEmpty fill title="没有匹配的审计记录" description="换一个操作者、动作或时间范围再试。" />
      ) : (
        <div className="audit-list">
          {items.map((event) => {
            const connectionId = parseAuditConnectionId(event.target);
            const expanded = expandedId === event.id;
            const detail = fullDetail[event.id] ?? event.detail;
            return (
              <article className={`audit-item${isDangerousAuditAction(event.action) ? ' is-dangerous' : ''}`} key={event.id}>
                <div className="audit-item-heading">
                  <Space size={6} wrap>
                    <Tag color={auditActionColor(event.action)}>{auditActionLabel(event.action)}</Tag>
                    <Text strong>{event.actor}</Text>
                    <Text type="secondary">{formatHistoryTime(event.createdAt)}</Text>
                  </Space>
                  {detail && (
                    <Button size="small" type="link" onClick={() => void toggleDetail(event)}>
                      {expanded ? '收起' : '详情'}
                    </Button>
                  )}
                </div>
                <Text type="secondary" className="audit-item-target">
                  {auditTargetLabel(event.target, connectionId ? connectionNames.get(connectionId) : undefined)}
                </Text>
                {expanded && detail && (
                  <>
                    <pre className="audit-item-detail">
                      {detail}
                      {event.detailTruncated && !fullDetail[event.id] ? '\n…（正在加载完整内容）' : ''}
                    </pre>
                    <div className="audit-request-context">
                      <Text type="secondary">请求 ID：{event.requestId || '—'} · TCP 对端：{event.remoteAddress || '—'}{event.forwardedFor ? ` · X-Forwarded-For：${event.forwardedFor}` : ''}</Text>
                      {event.userAgent && <Text type="secondary">User-Agent：{event.userAgent}</Text>}
                    </div>
                  </>
                )}
              </article>
            );
          })}
        </div>
      )}

      <div className="audit-footer">
        <Text type="secondary">{auditPageSummary(items.length, query.page, page?.hasMore ?? false)}</Text>
        <Space size={4}>
          <Button
            size="small"
            icon={<LeftOutlined />}
            disabled={loading || query.page <= 0}
            onClick={() => void load({ ...query, page: query.page - 1 })}
          >
            上一页
          </Button>
          <Button
            size="small"
            icon={<RightOutlined />}
            iconPlacement="end"
            disabled={loading || !page?.hasMore}
            onClick={() => void load({ ...query, page: query.page + 1 })}
          >
            下一页
          </Button>
        </Space>
      </div>
    </div>
  );
});
