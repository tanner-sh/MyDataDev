import { lazy, memo, Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Button, Input, Segmented, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { CloseOutlined, CodeOutlined, PlusOutlined, ReloadOutlined, TableOutlined } from '@ant-design/icons';
import {
  explorerObjectCountLabel,
  explorerObjectKindLabel,
  explorerObjectKinds,
  normalizeExplorerObjectKind,
  schemaObjectCapabilities
} from '../schemaObjectModel';
import type { ExplorerObjectCount, ExplorerObjectKind } from '../schemaObjectModel';
import type { Connection, DbObject, Metadata, SchemaObjectKind } from '../types';
import { dbTypeLabel } from '../utils';
import { ObjectTree } from './ObjectTree';
import type { SchemaObjectListSummary } from './SchemaObjectManager';
import {
  objectPreferenceKey,
  readFavoriteObjectKeys,
  readRecentObjects,
  rememberRecentObject,
  writeFavoriteObjectKeys
} from '../workspacePreferences';

const { Text } = Typography;
const SchemaObjectManager = lazy(() => import('./SchemaObjectManager').then((module) => ({ default: module.SchemaObjectManager })));

export type ResourceExplorerProps = {
  compactLayout: boolean;
  selected: Connection | null;
  metadata: Metadata | null;
  metadataQuery: { schema: string; keyword: string };
  metadataAppliedKeyword: string;
  metadataLoading: boolean;
  metadataBlockingLoading: boolean;
  connectionsError: string;
  structureLoadingKey: string | null;
  objects: DbObject[];
  activeObject: Pick<DbObject, 'schemaName' | 'name'> | null;
  namespaceLabel: string;
  onRefresh: () => void;
  onClose: () => void;
  onOpenConnections: () => void;
  onSchemaChange: (schema: string, kind: ExplorerObjectKind) => void;
  onKeywordChange: (keyword: string, kind: ExplorerObjectKind) => void;
  onSearch: (keyword: string, kind: ExplorerObjectKind) => void;
  onLoadMore: () => void;
  onLoadStructure: (object: DbObject) => Promise<DbObject | null>;
  onOpenDetail: (object: DbObject) => void;
  onOpenTable: (object: DbObject) => void;
  onBackupTable: (object: DbObject) => void;
  tableLifecycleEnabled: boolean;
  onCreateTable: () => void;
  onRenameTable: (object: DbObject) => void;
  onDropTable: (object: DbObject) => void;
};

export const ResourceExplorer = memo(function ResourceExplorer({
  compactLayout,
  selected,
  metadata,
  metadataQuery,
  metadataAppliedKeyword,
  metadataLoading,
  metadataBlockingLoading,
  connectionsError,
  structureLoadingKey,
  objects,
  activeObject,
  namespaceLabel,
  onRefresh,
  onClose,
  onOpenConnections,
  onSchemaChange,
  onKeywordChange,
  onSearch,
  onLoadMore,
  onLoadStructure,
  onOpenDetail,
  onOpenTable,
  onBackupTable,
  tableLifecycleEnabled,
  onCreateTable,
  onRenameTable,
  onDropTable
}: ResourceExplorerProps) {
  const [activeKind, setActiveKind] = useState<ExplorerObjectKind>('TABLE');
  const [objectView, setObjectView] = useState<'all' | 'favorites' | 'recent'>('all');
  const [favoriteObjectKeys, setFavoriteObjectKeys] = useState<string[]>(() => readFavoriteObjectKeys());
  const [recentObjects, setRecentObjects] = useState(() => readRecentObjects());
  const [keyboardObjectIndex, setKeyboardObjectIndex] = useState(-1);
  const [schemaObjectRefreshToken, setSchemaObjectRefreshToken] = useState(0);
  const [schemaObjectLoadMoreToken, setSchemaObjectLoadMoreToken] = useState(0);
  const [schemaCreateKind, setSchemaCreateKind] = useState<SchemaObjectKind>();
  const [schemaCounts, setSchemaCounts] = useState<Partial<Record<SchemaObjectKind, SchemaObjectListSummary>>>({});
  const schemaCapabilities = useMemo(() => schemaObjectCapabilities(selected?.capabilities), [selected?.capabilities]);
  const supportedKinds = useMemo(() => explorerObjectKinds(selected?.capabilities), [selected?.capabilities]);
  const managesViews = schemaCapabilities.some((capability) => capability.kind === 'VIEW');
  const tableObjects = useMemo(
    () => managesViews ? objects.filter((object) => !object.type.toUpperCase().includes('VIEW')) : objects,
    [managesViews, objects]
  );
  const favoriteObjectKeySet = useMemo(() => new Set(favoriteObjectKeys), [favoriteObjectKeys]);
  const visibleTableObjects = useMemo(() => {
    if (!selected || objectView === 'all') return tableObjects;
    if (objectView === 'favorites') {
      return tableObjects.filter((object) => favoriteObjectKeySet.has(objectPreferenceKey(selected.id, object)));
    }
    const byKey = new Map(tableObjects.map((object) => [objectPreferenceKey(selected.id, object), object]));
    return recentObjects
      .filter((entry) => entry.connectionId === selected.id)
      .map((entry) => byKey.get(entry.key))
      .filter((object): object is DbObject => Boolean(object));
  }, [favoriteObjectKeySet, objectView, recentObjects, selected, tableObjects]);
  const favoriteCount = useMemo(() => selected
    ? tableObjects.filter((object) => favoriteObjectKeySet.has(objectPreferenceKey(selected.id, object))).length
    : 0, [favoriteObjectKeySet, selected, tableObjects]);
  const recentCount = useMemo(() => selected
    ? recentObjects.filter((entry) => entry.connectionId === selected.id && tableObjects.some((object) => objectPreferenceKey(selected.id, object) === entry.key)).length
    : 0, [recentObjects, selected, tableObjects]);
  const activeSchemaKind = activeKind === 'TABLE' ? undefined : activeKind;
  const activeCapability = activeSchemaKind
    ? schemaCapabilities.find((capability) => capability.kind === activeSchemaKind)
    : undefined;
  const activeLabel = explorerObjectKindLabel(activeKind, managesViews);
  const tableCount: ExplorerObjectCount = { loaded: tableObjects.length, hasMore: metadata?.hasMore };
  const activeCount = activeKind === 'TABLE' ? tableCount : schemaCounts[activeKind];
  const activeLoading = activeKind === 'TABLE' ? metadataLoading : Boolean(activeCount?.loading);
  const canCreate = activeKind === 'TABLE'
    ? tableLifecycleEnabled
    : Boolean(!selected?.readonly && activeCapability?.operations.includes('CREATE'));

  useEffect(() => {
    setSchemaCounts({});
    setSchemaCreateKind(undefined);
    setObjectView('all');
    setKeyboardObjectIndex(-1);
  }, [selected?.id]);

  useEffect(() => {
    setKeyboardObjectIndex((current) => Math.min(current, visibleTableObjects.length - 1));
  }, [visibleTableObjects.length]);

  useEffect(() => {
    if (!supportedKinds.includes(activeKind)) {
      setActiveKind('TABLE');
      onSearch('', 'TABLE');
    }
  }, [activeKind, onSearch, supportedKinds]);

  const handleSummaryChange = useCallback((kind: SchemaObjectKind, summary?: SchemaObjectListSummary) => {
    setSchemaCounts((current) => current[kind] === summary ? current : { ...current, [kind]: summary });
  }, []);

  function changeObjectKind(kind: ExplorerObjectKind) {
    const nextKind = normalizeExplorerObjectKind(kind, selected?.capabilities);
    if (nextKind === activeKind) return;
    setActiveKind(nextKind);
    setSchemaCreateKind(undefined);
    onSearch('', nextKind);
  }

  function refreshCurrentKind() {
    if (activeKind === 'TABLE') {
      onRefresh();
    } else {
      setSchemaObjectRefreshToken((current) => current + 1);
    }
  }

  function createCurrentKind() {
    if (activeKind === 'TABLE') {
      onCreateTable();
    } else {
      setSchemaCreateKind(activeKind);
    }
  }

  function kindIcon(kind: ExplorerObjectKind) {
    return kind === 'TABLE' ? <TableOutlined /> : <CodeOutlined />;
  }

  function rememberObject(object: DbObject) {
    if (!selected) return;
    setRecentObjects((current) => rememberRecentObject(current, selected.id, object));
  }

  function openObjectDetail(object: DbObject) {
    rememberObject(object);
    onOpenDetail(object);
  }

  function openTableData(object: DbObject) {
    rememberObject(object);
    onOpenTable(object);
  }

  function toggleFavoriteObject(object: DbObject) {
    if (!selected) return;
    const key = objectPreferenceKey(selected.id, object);
    setFavoriteObjectKeys((current) => {
      const next = current.includes(key) ? current.filter((item) => item !== key) : [...current, key];
      writeFavoriteObjectKeys(next);
      return next;
    });
  }

  function handleObjectSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (activeKind !== 'TABLE' || visibleTableObjects.length === 0) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setKeyboardObjectIndex((current) => Math.min(visibleTableObjects.length - 1, current + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setKeyboardObjectIndex((current) => Math.max(0, current - 1));
    } else if (event.key === 'Enter' && keyboardObjectIndex >= 0) {
      event.preventDefault();
      openObjectDetail(visibleTableObjects[keyboardObjectIndex]);
    }
  }

  const kindOptions = supportedKinds.map((kind) => {
    const count = kind === 'TABLE' ? tableCount : schemaCounts[kind];
    return {
      value: kind,
      label: (
        <span className="object-kind-option">
          <span className="object-kind-option-main">{kindIcon(kind)}<span>{explorerObjectKindLabel(kind, managesViews)}</span></span>
          <span className="object-kind-count">{explorerObjectCountLabel(count)}</span>
        </span>
      )
    };
  });

  const footerStatus = activeLoading && !activeCount?.loaded
    ? `正在加载${activeLabel}…`
    : activeCount
    ? `已加载 ${activeCount.loaded}${activeCount.total != null ? ` / ${activeCount.total}` : activeCount.hasMore ? '+' : ''} 个${activeLabel}对象`
    : `尚未加载${activeLabel}`;
  const cacheStatus = activeCount?.cachedAt
    ? ` · ${activeCount.cacheHit ? '缓存数据' : '刚刚刷新'} · ${new Date(activeCount.cachedAt).toLocaleTimeString()}`
    : activeKind === 'TABLE' && metadata?.cachedAt
      ? ` · ${metadata.cacheHit ? '缓存数据' : '刚刚刷新'} · ${new Date(metadata.cachedAt).toLocaleTimeString()}`
      : '';

  return (
    <div className="resource-explorer">
      <div className="explorer-header">
        <div>
          <Text strong>资源管理器</Text>
          <Text type="secondary">数据库对象</Text>
        </div>
        <Space size={2}>
          <Tooltip title={`刷新${activeLabel}`}>
            <Button type="text" size="small" icon={<ReloadOutlined />} loading={activeLoading} disabled={!selected}
                    aria-label={`刷新${activeLabel}`} onClick={refreshCurrentKind}>刷新</Button>
          </Tooltip>
          {compactLayout && (
            <Tooltip title="关闭资源管理器">
              <Button type="text" size="small" icon={<CloseOutlined />} aria-label="关闭资源管理器" onClick={onClose} />
            </Tooltip>
          )}
        </Space>
      </div>

      {selected ? (
        <Tooltip title={selected.jdbcUrl} placement="right">
          <div className="explorer-connection-summary">
            <span className="connection-dot" aria-hidden="true" />
            <Text strong ellipsis>{selected.name}</Text>
            <Tag variant="filled" color="blue">{dbTypeLabel(selected.dbType)}</Tag>
            {selected.readonly && <Tag variant="filled" color="orange">只读</Tag>}
          </div>
        </Tooltip>
      ) : (
        <button className="explorer-empty-connection" onClick={onOpenConnections}>尚未选择连接，打开连接管理</button>
      )}

      <div className="explorer-filters">
        <Select
          size="small"
          allowClear
          showSearch
          className="full-width"
          placeholder={`选择${namespaceLabel}`}
          value={metadataQuery.schema || metadata?.selectedSchema || undefined}
          disabled={!selected || metadataBlockingLoading}
          options={(metadata?.schemas || []).map((schema) => ({
            value: schema,
            label: schema === metadata?.currentSchema ? `${schema}（当前）` : schema
          }))}
          onChange={(schema) => onSchemaChange(schema || '', activeKind)}
        />
        <div className="object-type-toolbar">
          <Select<ExplorerObjectKind>
            size="small"
            className="object-kind-select"
            aria-label="选择数据库对象类型"
            value={activeKind}
            options={kindOptions}
            onChange={changeObjectKind}
          />
          {canCreate && (
            <Tooltip title={`新建${activeLabel}`}>
              <Button type="text" size="small" icon={<PlusOutlined />} aria-label={`新建${activeLabel}`}
                      disabled={metadataBlockingLoading} onClick={createCurrentKind} />
            </Tooltip>
          )}
        </div>
        <Input.Search
          size="small"
          allowClear
          loading={activeLoading && !metadataBlockingLoading}
          placeholder={`搜索${activeLabel}`}
          disabled={!selected || metadataBlockingLoading}
          value={metadataQuery.keyword}
          onChange={(event) => {
            setKeyboardObjectIndex(-1);
            onKeywordChange(event.target.value, activeKind);
          }}
          onKeyDown={handleObjectSearchKeyDown}
          onSearch={(keyword) => onSearch(keyword, activeKind)}
        />
        {activeKind === 'TABLE' && (
          <div className="object-view-filter">
            <Segmented
              block
              size="small"
              aria-label="筛选表对象"
              value={objectView}
              options={[
                { value: 'all', label: `全部 ${tableObjects.length}` },
                { value: 'favorites', label: `收藏 ${favoriteCount}` },
                { value: 'recent', label: `最近 ${recentCount}` }
              ]}
              onChange={(value) => {
                setObjectView(value as typeof objectView);
                setKeyboardObjectIndex(-1);
              }}
            />
            <Text type="secondary">↑↓ 选择，Enter 查看 · 当前显示 {visibleTableObjects.length} 个</Text>
          </div>
        )}
      </div>

      {connectionsError && <div className="explorer-error" role="alert">{connectionsError}</div>}
      <div className="object-navigation-scroll" aria-busy={activeLoading}>
        {activeKind === 'TABLE' && (metadataBlockingLoading ? (
          <div className="explorer-loading" role="status">
            <Spin size="small" />
            <Text type="secondary">正在加载 {metadataQuery.schema || `当前${namespaceLabel}`}…</Text>
          </div>
        ) : (
          <ObjectTree
            key={`${selected?.id || 'none'}:${metadata?.selectedSchema || 'current-schema'}:${metadataAppliedKeyword}`}
            scrollScopeKey={objectView}
            showTypeGroups={false}
            objects={visibleTableObjects}
            activeObject={activeObject}
            keyboardObject={visibleTableObjects[keyboardObjectIndex]}
            keyword={metadataAppliedKeyword}
            emptyDescription={objectView === 'favorites'
              ? '当前已加载对象中暂无收藏表'
              : objectView === 'recent'
                ? '当前已加载对象中暂无最近访问的表'
                : metadataAppliedKeyword ? '未找到匹配的表' : `当前${namespaceLabel}暂无表`}
            structureLoadingKey={structureLoadingKey}
            hasMore={objectView === 'all' ? metadata?.hasMore : false}
            loadingMore={objectView === 'all' && metadataLoading && !metadataBlockingLoading}
            onLoadMore={objectView === 'all' ? onLoadMore : undefined}
            onLoadStructure={onLoadStructure}
            onOpenDetail={openObjectDetail}
            onOpenTable={openTableData}
            onBackupTable={onBackupTable}
            isObjectFavorite={(object) => Boolean(selected && favoriteObjectKeySet.has(objectPreferenceKey(selected.id, object)))}
            onToggleFavorite={toggleFavoriteObject}
            tableLifecycleEnabled={tableLifecycleEnabled}
            onRenameTable={onRenameTable}
            onDropTable={onDropTable}
          />
        ))}
        {selected && activeSchemaKind && (
          <Suspense fallback={<div className="schema-object-group-status"><Spin size="small" /> 正在加载{activeLabel}…</div>}>
            <SchemaObjectManager
              connection={selected}
              schemaName={metadataQuery.schema || metadata?.selectedSchema}
              keyword={metadataAppliedKeyword}
              activeKind={activeSchemaKind}
              createKind={schemaCreateKind}
              refreshToken={schemaObjectRefreshToken}
              loadMoreToken={schemaObjectLoadMoreToken}
              onCloseCreate={() => setSchemaCreateKind(undefined)}
              onSummaryChange={handleSummaryChange}
              onOpenViewData={onOpenTable}
            />
          </Suspense>
        )}
        {activeKind === 'TABLE' && metadataLoading && !metadataBlockingLoading && (
          <div className="explorer-tree-loading-indicator" role="status">
            <Spin size="small" />
            <span>正在更新对象…</span>
          </div>
        )}
      </div>
      <div className="explorer-footer">
        <span className="metadata-cache-status">{footerStatus}{cacheStatus}</span>
        {activeCount?.hasMore && (
          <Button size="small" block disabled={activeLoading} onClick={() => {
            if (activeKind === 'TABLE') onLoadMore();
            else setSchemaObjectLoadMoreToken((current) => current + 1);
          }}>加载更多{activeLabel}</Button>
        )}
      </div>
    </div>
  );
});
