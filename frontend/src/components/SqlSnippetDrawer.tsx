import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { Button, Drawer, Empty, Input, Modal, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons';
import { api } from '../api';
import { DB_TYPE_OPTIONS, DRAWER_WIDTH } from '../constants';
import { localizeError } from '../utils';
import {
  EMPTY_SNIPPET_DRAFT,
  parseSnippetTags,
  snippetDraftFrom,
  snippetListParams,
  snippetRequestBody,
  snippetSubtitle,
  SNIPPET_SEARCH_DEBOUNCE_MS,
  validateSnippetDraft,
  type SqlSnippet,
  type SqlSnippetDraft
} from '../sqlSnippets';

const { Text } = Typography;

/**
 * 保存的 SQL 片段。
 *
 * 默认只列出与当前连接类型兼容的片段（通用 + 同类型）—— 一条 Oracle 的 ROWNUM 查询出现在
 * MySQL 连接的候选里只会添乱；勾掉筛选可以看全部。
 */
export const SqlSnippetDrawer = memo(function SqlSnippetDrawer({
  open,
  dbType,
  pendingDraft,
  onClose,
  onInsert
}: {
  open: boolean;
  dbType?: string;
  /** 从编辑器「保存为片段」带进来的草稿。 */
  pendingDraft?: { draft: SqlSnippetDraft; token: number };
  onClose: () => void;
  onInsert: (snippet: SqlSnippet) => void;
}) {
  const [snippets, setSnippets] = useState<SqlSnippet[]>([]);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [scopeToDbType, setScopeToDbType] = useState(true);
  const [editing, setEditing] = useState<SqlSnippetDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<SqlSnippet | null>(null);
  const requestSeqRef = useRef(0);
  const debounceRef = useRef<number | null>(null);

  const load = useCallback(async (search: string, scoped: boolean) => {
    const requestId = ++requestSeqRef.current;
    setLoading(true);
    setError('');
    try {
      const query = snippetListParams(search, scoped ? dbType : undefined);
      const rows = await api<SqlSnippet[]>(`/sql-snippets${query ? `?${query}` : ''}`);
      if (requestId !== requestSeqRef.current) return;
      setSnippets(rows);
    } catch (e) {
      if (requestId !== requestSeqRef.current) return;
      setError(localizeError(e));
    } finally {
      if (requestId === requestSeqRef.current) setLoading(false);
    }
  }, [dbType]);

  useEffect(() => {
    if (!open) return;
    setKeyword('');
    setEditing(null);
    void load('', scopeToDbType);
  }, [load, open, scopeToDbType]);

  // 从编辑器点「保存为片段」时直接进入编辑态。
  useEffect(() => {
    if (pendingDraft) setEditing(pendingDraft.draft);
  }, [pendingDraft?.token]);

  useEffect(() => () => {
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
  }, []);

  function queueSearch(value: string) {
    setKeyword(value);
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null;
      void load(value, scopeToDbType);
    }, SNIPPET_SEARCH_DEBOUNCE_MS);
  }

  async function save() {
    if (!editing) return;
    const validation = validateSnippetDraft(editing);
    if (!validation.valid) return;
    setSaving(true);
    setError('');
    try {
      const body = JSON.stringify(snippetRequestBody(editing));
      if (editing.id) await api<SqlSnippet>(`/sql-snippets/${editing.id}`, { method: 'PUT', body });
      else await api<SqlSnippet>('/sql-snippets', { method: 'POST', body });
      setEditing(null);
      await load(keyword, scopeToDbType);
    } catch (e) {
      setError(localizeError(e));
    } finally {
      setSaving(false);
    }
  }

  async function insert(snippet: SqlSnippet) {
    onInsert(snippet);
    onClose();
    // 使用次数用于把常用片段排到前面，失败不影响插入本身。
    api<SqlSnippet>(`/sql-snippets/${snippet.id}/use`, { method: 'POST' }).catch(() => undefined);
  }

  async function remove(snippet: SqlSnippet) {
    setPendingDelete(null);
    try {
      await api<{ ok: boolean }>(`/sql-snippets/${snippet.id}`, { method: 'DELETE' });
      await load(keyword, scopeToDbType);
    } catch (e) {
      setError(localizeError(e));
    }
  }

  const validation = editing ? validateSnippetDraft(editing) : undefined;

  return (
    <Drawer
      title="SQL 片段"
      size={DRAWER_WIDTH.browse}
      open={open}
      rootClassName="management-drawer"
      extra={
        <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setEditing({ ...EMPTY_SNIPPET_DRAFT, dbType: dbType || '' })}>
          新建片段
        </Button>
      }
      onClose={onClose}
    >
      {editing ? (
        <div className="snippet-editor">
          <label className="snippet-field">
            <Text strong>名称</Text>
            <Input
              autoFocus
              value={editing.name}
              status={validation?.nameError ? 'error' : undefined}
              placeholder="例如：每日对账"
              onChange={(event) => setEditing({ ...editing, name: event.target.value })}
            />
            {validation?.nameError && <Text type="danger">{validation.nameError}</Text>}
          </label>
          <label className="snippet-field">
            <Text strong>说明</Text>
            <Input
              value={editing.description}
              placeholder="这段 SQL 用来做什么（可选）"
              onChange={(event) => setEditing({ ...editing, description: event.target.value })}
            />
          </label>
          <div className="snippet-field-row">
            <label className="snippet-field">
              <Text strong>适用数据库</Text>
              <Select
                allowClear
                value={editing.dbType || undefined}
                placeholder="通用（所有类型）"
                options={DB_TYPE_OPTIONS.map((option) => ({ value: option.value, label: option.label }))}
                onChange={(value) => setEditing({ ...editing, dbType: value || '' })}
              />
            </label>
            <label className="snippet-field">
              <Text strong>标签</Text>
              <Input
                value={editing.tags}
                placeholder="对账 日常（空格或逗号分隔）"
                onChange={(event) => setEditing({ ...editing, tags: event.target.value })}
              />
            </label>
          </div>
          <label className="snippet-field snippet-field-grow">
            <Text strong>SQL</Text>
            <Input.TextArea
              className="snippet-sql-input"
              value={editing.sql}
              status={validation?.sqlError ? 'error' : undefined}
              autoSize={{ minRows: 8, maxRows: 20 }}
              onChange={(event) => setEditing({ ...editing, sql: event.target.value })}
            />
            {validation?.sqlError && <Text type="danger">{validation.sqlError}</Text>}
          </label>
          {error && <Text type="danger">{error}</Text>}
          <Space>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={!validation?.valid} onClick={() => void save()}>
              保存
            </Button>
            <Button onClick={() => { setEditing(null); setError(''); }}>取消</Button>
          </Space>
        </div>
      ) : (
        <>
          <Input.Search
            allowClear
            className="snippet-search"
            placeholder="搜索名称、说明、SQL 或标签"
            value={keyword}
            loading={loading}
            onChange={(event) => queueSearch(event.target.value)}
            onSearch={(value) => void load(value, scopeToDbType)}
          />
          {dbType && (
            <Button
              size="small"
              type="link"
              className="snippet-scope-toggle"
              onClick={() => setScopeToDbType((current) => !current)}
            >
              {scopeToDbType ? `仅显示适用于 ${dbType.toUpperCase()} 的片段 · 查看全部` : '正在显示全部片段 · 只看当前类型'}
            </Button>
          )}
          {error && <Text type="danger" className="snippet-error">{error}</Text>}
          {loading && snippets.length === 0 ? (
            <div className="snippet-loading"><Spin size="small" /> <Text type="secondary">正在加载片段…</Text></div>
          ) : snippets.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={keyword ? '没有匹配的片段' : '还没有保存任何片段。在编辑器里写好 SQL 后点「保存为片段」。'}
            />
          ) : (
            <div className="snippet-list">
              {snippets.map((snippet) => (
                <article className="snippet-item" key={snippet.id}>
                  <div className="snippet-item-heading">
                    <div className="snippet-item-title">
                      <Text strong ellipsis>{snippet.name}</Text>
                      <Text type="secondary">{snippetSubtitle(snippet)}</Text>
                    </div>
                    <Space size={2}>
                      <Tooltip title="插入到当前编辑器">
                        <Button size="small" type="primary" ghost onClick={() => void insert(snippet)}>插入</Button>
                      </Tooltip>
                      <Tooltip title="编辑">
                        <Button size="small" type="text" icon={<EditOutlined />} aria-label={`编辑 ${snippet.name}`} onClick={() => setEditing(snippetDraftFrom(snippet))} />
                      </Tooltip>
                      <Tooltip title="删除">
                        <Button size="small" type="text" icon={<DeleteOutlined />} aria-label={`删除 ${snippet.name}`} onClick={() => setPendingDelete(snippet)} />
                      </Tooltip>
                    </Space>
                  </div>
                  {snippet.description && <Text type="secondary" className="snippet-item-description">{snippet.description}</Text>}
                  <pre className="snippet-item-sql">{snippet.sql}</pre>
                  {parseSnippetTags(snippet.tags).length > 0 && (
                    <Space size={4} wrap>
                      {parseSnippetTags(snippet.tags).map((tag) => <Tag key={tag}>{tag}</Tag>)}
                    </Space>
                  )}
                </article>
              ))}
            </div>
          )}
        </>
      )}
      <Modal
        open={pendingDelete !== null}
        title={pendingDelete ? `删除片段「${pendingDelete.name}」？` : undefined}
        okText="删除"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onOk={() => pendingDelete && void remove(pendingDelete)}
        onCancel={() => setPendingDelete(null)}
        destroyOnHidden
      >
        <Text type="secondary">删除后无法恢复。</Text>
      </Modal>
    </Drawer>
  );
});
