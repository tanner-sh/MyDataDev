import { memo, useMemo, useState } from 'react';
import { Button, Input, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { CloseOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { Connection, DbObject, Metadata } from '../types';
import { dbTypeLabel } from '../utils';
import { ObjectTree } from './ObjectTree';
import { SchemaObjectManager } from './SchemaObjectManager';

const { Text } = Typography;

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
  onSchemaChange: (schema: string) => void;
  onKeywordChange: (keyword: string) => void;
  onSearch: (keyword: string) => void;
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
  const [schemaObjectRefreshToken, setSchemaObjectRefreshToken] = useState(0);
  const managesViews = Boolean(selected?.capabilities.schemaObjects?.some((capability) => capability.kind === 'VIEW'));
  const tableObjects = useMemo(
    () => managesViews ? objects.filter((object) => !object.type.toUpperCase().includes('VIEW')) : objects,
    [managesViews, objects]
  );

  function refreshAllObjects() {
    setSchemaObjectRefreshToken((current) => current + 1);
    onRefresh();
  }

  return (
    <div className="resource-explorer">
      <div className="explorer-header">
        <div>
          <Text strong>资源管理器</Text>
          <Text type="secondary">数据库对象</Text>
        </div>
        <Space size={2}>
          <Tooltip title={tableLifecycleEnabled ? '在当前命名空间新建表' : '当前连接不支持表对象管理'}>
            <Button
              type="text"
              size="small"
              icon={<PlusOutlined />}
              disabled={!selected || !tableLifecycleEnabled || metadataBlockingLoading}
              aria-label="新建表"
              onClick={onCreateTable}
            >
              新建表
            </Button>
          </Tooltip>
          <Tooltip title="刷新对象缓存">
            <Button type="text" size="small" icon={<ReloadOutlined />} loading={metadataLoading} disabled={!selected}
                    aria-label="刷新对象缓存" onClick={refreshAllObjects}>刷新</Button>
          </Tooltip>
          {compactLayout && (
            <Tooltip title="关闭资源管理器">
              <Button type="text" size="small" icon={<CloseOutlined />} aria-label="关闭资源管理器" onClick={onClose} />
            </Tooltip>
          )}
        </Space>
      </div>

      {selected ? (
        <div className="explorer-connection-summary">
          <div className="explorer-connection-title">
            <span className="connection-dot" aria-hidden="true" />
            <Text strong ellipsis>{selected.name}</Text>
            <Tag variant="filled" color="blue">{dbTypeLabel(selected.dbType)}</Tag>
            {selected.readonly && <Tag variant="filled" color="orange">只读</Tag>}
          </div>
          <Text type="secondary" ellipsis title={selected.jdbcUrl}>{selected.jdbcUrl}</Text>
        </div>
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
          onChange={(schema) => onSchemaChange(schema || '')}
        />
        <Input.Search
          size="small"
          allowClear
          loading={metadataLoading && !metadataBlockingLoading}
          placeholder="搜索数据库对象"
          disabled={!selected || metadataBlockingLoading}
          value={metadataQuery.keyword}
          onChange={(event) => onKeywordChange(event.target.value)}
          onSearch={onSearch}
        />
      </div>

      {connectionsError && <div className="explorer-error" role="alert">{connectionsError}</div>}
      <div className="object-tree-scroll" aria-busy={metadataLoading}>
        {metadataBlockingLoading ? (
          <div className="explorer-loading" role="status">
            <Spin size="small" />
            <Text type="secondary">正在加载 {metadataQuery.schema || `当前${namespaceLabel}`}…</Text>
          </div>
        ) : (
          <ObjectTree
            key={`${selected?.id || 'none'}:${metadata?.selectedSchema || 'current-schema'}:${metadataAppliedKeyword}`}
            objects={tableObjects}
            activeObject={activeObject}
            keyword={metadataAppliedKeyword}
            emptyDescription={metadataAppliedKeyword ? '未找到匹配的表' : `当前${namespaceLabel}暂无表`}
            structureLoadingKey={structureLoadingKey}
            hasMore={metadata?.hasMore}
            loadingMore={metadataLoading && !metadataBlockingLoading}
            onLoadMore={onLoadMore}
            onLoadStructure={onLoadStructure}
            onOpenDetail={onOpenDetail}
            onOpenTable={onOpenTable}
            onBackupTable={onBackupTable}
            tableLifecycleEnabled={tableLifecycleEnabled}
            onRenameTable={onRenameTable}
            onDropTable={onDropTable}
          />
        )}
        {selected && !metadataBlockingLoading && (
          <SchemaObjectManager
            connection={selected}
            schemaName={metadataQuery.schema || metadata?.selectedSchema}
            keyword={metadataAppliedKeyword}
            refreshToken={schemaObjectRefreshToken}
            onOpenViewData={onOpenTable}
          />
        )}
        {metadataLoading && !metadataBlockingLoading && (
          <div className="explorer-tree-loading-indicator" role="status">
            <Spin size="small" />
            <span>正在更新对象…</span>
          </div>
        )}
      </div>
      <div className="explorer-footer">
        {metadata?.cachedAt && (
          <span className="metadata-cache-status">
            已展示 {objects.length}/{metadata.totalObjectsExact === false ? `至少 ${metadata.totalObjects}` : metadata.totalObjects} · {metadata.cacheHit ? '缓存数据' : '刚刚刷新'} · {new Date(metadata.cachedAt).toLocaleTimeString()}
          </span>
        )}
        {metadata?.hasMore && (
          <Button size="small" block disabled={metadataLoading} onClick={onLoadMore}>加载更多对象</Button>
        )}
      </div>
    </div>
  );
});
