import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Empty, Input, Modal, Spin, Tag, Typography } from 'antd';
import { CodeOutlined, EyeOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { api } from '../api';
import { localizeError } from '../utils';
import {
  groupObjectSearchHits,
  moveObjectSearchSelection,
  objectKindLabel,
  objectSearchHitKey,
  objectSearchRequestParams,
  objectSearchSummary,
  opensTableData,
  orderObjectSearchHits,
  OBJECT_SEARCH_DEBOUNCE_MS,
  type ObjectSearchHit,
  type ObjectSearchResult
} from '../objectSearch';

const { Text } = Typography;

function kindIcon(kind: string) {
  if (kind === 'TABLE') return <TableOutlined />;
  if (kind === 'VIEW' || kind === 'MATERIALIZED_VIEW') return <EyeOutlined />;
  return <CodeOutlined />;
}

/**
 * 命令面板式的全局对象搜索。
 *
 * 一个输入框同时搜表、视图、物化视图、存储过程、函数、触发器、序列 —— 不必先在资源
 * 管理器里选定类型。表回车直接打开数据（和对象树双击一致），其余打开定义。
 */
export const ObjectSearchPalette = memo(function ObjectSearchPalette({
  open,
  connectionId,
  schemaName,
  onClose,
  onOpenHit
}: {
  open: boolean;
  connectionId?: number;
  schemaName?: string;
  onClose: () => void;
  onOpenHit: (hit: ObjectSearchHit) => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [result, setResult] = useState<ObjectSearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState(-1);
  const requestSeqRef = useRef(0);
  const debounceRef = useRef<number | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const hits = useMemo(() => orderObjectSearchHits(result?.hits || []), [result]);
  const groups = useMemo(() => groupObjectSearchHits(result?.hits || []), [result]);

  const search = useCallback(async (value: string) => {
    if (!connectionId) return;
    const requestId = ++requestSeqRef.current;
    setLoading(true);
    setError('');
    try {
      const response = await api<ObjectSearchResult>(
        `/metadata/${connectionId}/search?${objectSearchRequestParams(value, schemaName)}`
      );
      if (requestId !== requestSeqRef.current) return;
      setResult(response);
      setSelected(response.hits.length > 0 ? 0 : -1);
    } catch (e) {
      if (requestId !== requestSeqRef.current) return;
      setError(localizeError(e));
      setResult(null);
    } finally {
      if (requestId === requestSeqRef.current) setLoading(false);
    }
  }, [connectionId, schemaName]);

  useEffect(() => {
    if (!open) return;
    setKeyword('');
    setSelected(-1);
    setError('');
    void search('');
  }, [open, search]);

  useEffect(() => () => {
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
  }, []);

  // 选中项跟着键盘移动时保持在可视范围内。
  useEffect(() => {
    if (selected < 0) return;
    listRef.current
      ?.querySelectorAll('.object-search-hit')[selected]
      ?.scrollIntoView({ block: 'nearest' });
  }, [selected]);

  function queueSearch(value: string) {
    setKeyword(value);
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null;
      void search(value);
    }, OBJECT_SEARCH_DEBOUNCE_MS);
  }

  function activate(hit: ObjectSearchHit) {
    onOpenHit(hit);
    onClose();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setSelected((current) => moveObjectSearchSelection(current, hits.length, event.key === 'ArrowDown' ? 1 : -1));
      return;
    }
    if (event.key === 'Enter' && selected >= 0 && hits[selected]) {
      event.preventDefault();
      activate(hits[selected]);
    }
  }

  let flatIndex = -1;

  return (
    <Modal
      open={open}
      title={null}
      footer={null}
      closable={false}
      width={640}
      rootClassName="object-search-modal"
      onCancel={onClose}
      destroyOnHidden
    >
      <Input
        autoFocus
        size="large"
        variant="borderless"
        prefix={<SearchOutlined />}
        suffix={loading ? <Spin size="small" /> : undefined}
        placeholder="搜索表、视图、存储过程、函数、触发器、序列…"
        aria-label="搜索数据库对象"
        value={keyword}
        onChange={(event) => queueSearch(event.target.value)}
        onKeyDown={handleKeyDown}
      />
      <div className="object-search-results" ref={listRef}>
        {error ? (
          <Text type="danger" className="object-search-error">{error}</Text>
        ) : hits.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={objectSearchSummary(0, false, keyword)} />
        ) : (
          groups.map((group) => (
            <section className="object-search-group" key={group.kind}>
              <Text type="secondary" className="object-search-group-label">{group.label}</Text>
              {group.hits.map((item) => {
                flatIndex += 1;
                const index = flatIndex;
                return (
                  <button
                    type="button"
                    key={objectSearchHitKey(item)}
                    className={`object-search-hit${index === selected ? ' is-selected' : ''}`}
                    onMouseEnter={() => setSelected(index)}
                    onClick={() => activate(item)}
                  >
                    <span className="object-search-hit-icon" aria-hidden="true">{kindIcon(item.kind)}</span>
                    <span className="object-search-hit-name">{item.displayName}</span>
                    {item.schemaName && <Text type="secondary" className="object-search-hit-schema">{item.schemaName}</Text>}
                    <Tag className="object-search-hit-kind">{objectKindLabel(item.kind)}</Tag>
                    {opensTableData(item) && <Text type="secondary" className="object-search-hit-action">回车看数据</Text>}
                  </button>
                );
              })}
            </section>
          ))
        )}
      </div>
      <div className="object-search-footer">
        <Text type="secondary">{objectSearchSummary(hits.length, result?.truncated ?? false, keyword)}</Text>
        <Text type="secondary">↑↓ 选择 · Enter 打开 · Esc 关闭{result?.schemaName ? ` · 范围 ${result.schemaName}` : ''}</Text>
      </div>
    </Modal>
  );
});
