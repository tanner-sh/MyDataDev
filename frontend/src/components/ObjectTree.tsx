import {
  CaretDownOutlined,
  CaretRightOutlined,
  CloudDownloadOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  InfoCircleOutlined,
  KeyOutlined,
  MoreOutlined,
  TableOutlined,
  UnorderedListOutlined
} from '@ant-design/icons';
import { Button, Dropdown, Empty, Spin, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, Key, ReactNode } from 'react';
import {
  collapseObjectBranch,
  createObjectOpenIntent,
  databaseObjectNodeKey,
  findMatchingDatabaseObject,
  groupDatabaseObjects,
  keepOnlyObjectBranch,
  objectStructureKey,
  treeNodeKey
} from '../objectTreeModel';
import type { DbObject } from '../types';

export type ObjectTreeProps = {
  objects: DbObject[];
  activeObject?: Pick<DbObject, 'schemaName' | 'name'> | null;
  keyword?: string;
  emptyDescription?: string;
  structureLoadingKey?: string | null;
  onLoadStructure: (object: DbObject) => Promise<DbObject | null>;
  onOpenDetail: (object: DbObject) => void;
  onOpenTable: (object: DbObject) => void;
  onBackupTable?: (object: DbObject) => void;
};

type AggregatedIndex = { name: string; columns: string[]; unique: boolean };

function objectIcon(object: DbObject) {
  return object.type.toUpperCase().includes('VIEW') ? <EyeOutlined /> : <TableOutlined />;
}

function fullObjectName(object: Pick<DbObject, 'schemaName' | 'name'>) {
  return object.schemaName ? `${object.schemaName}.${object.name}` : object.name;
}

function rowStyle(level: number) {
  return { '--object-tree-level': level } as CSSProperties;
}

function aggregateIndexes(object: DbObject): AggregatedIndex[] {
  const indexes = new Map<string, AggregatedIndex>();
  object.indexes.forEach((index) => {
    const identity = index.name || `${index.columnName}-index`;
    const existing = indexes.get(identity);
    if (existing) {
      if (!existing.columns.includes(index.columnName)) existing.columns.push(index.columnName);
      return;
    }
    indexes.set(identity, { name: index.name || '未命名索引', columns: [index.columnName], unique: index.unique });
  });
  return [...indexes.values()].sort((left, right) => left.name.localeCompare(right.name, 'zh-CN', { numeric: true, sensitivity: 'base' }));
}

function HighlightedName({ name, keyword }: { name: string; keyword?: string }) {
  const normalizedKeyword = keyword?.trim().toLocaleLowerCase();
  if (!normalizedKeyword) return <>{name}</>;
  const start = name.toLocaleLowerCase().indexOf(normalizedKeyword);
  if (start < 0) return <>{name}</>;
  return (
    <>
      {name.slice(0, start)}
      <mark className="object-tree-match">{name.slice(start, start + normalizedKeyword.length)}</mark>
      {name.slice(start + normalizedKeyword.length)}
    </>
  );
}

function TreeSwitcher({ expanded, label, onClick }: { expanded: boolean; label: string; onClick: () => void }) {
  return (
    <button className="object-tree-switcher" type="button" aria-label={label} onClick={onClick}>
      {expanded ? <CaretDownOutlined /> : <CaretRightOutlined />}
    </button>
  );
}

function TreeIcon({ children }: { children?: ReactNode }) {
  return <span className="object-tree-row-icon" aria-hidden="true">{children}</span>;
}

export function ObjectTree({
  objects,
  activeObject,
  keyword,
  emptyDescription = '当前 Schema 暂无数据库对象',
  structureLoadingKey,
  onLoadStructure,
  onOpenDetail,
  onOpenTable,
  onBackupTable
}: ObjectTreeProps) {
  const openIntentRef = useRef<ReturnType<typeof createObjectOpenIntent> | null>(null);
  if (!openIntentRef.current) openIntentRef.current = createObjectOpenIntent();
  const groups = useMemo(() => groupDatabaseObjects(objects), [objects]);
  const groupKeys = useMemo(() => groups.map((group) => treeNodeKey('object-type', group.objects[0]?.schemaName || '', group.key)), [groups]);
  const groupKeySignature = groupKeys.join('|');
  const objectListSignature = groups.flatMap((group) => group.objects.map((object) => databaseObjectNodeKey(object))).join('|');
  const [expandedKeys, setExpandedKeys] = useState<Key[]>(groupKeys);
  const [structureErrors, setStructureErrors] = useState<Set<string>>(() => new Set());

  useEffect(() => {
    setExpandedKeys((current) => [...new Set([...groupKeys, ...current])]);
  }, [groupKeySignature]);

  useEffect(() => () => openIntentRef.current?.cancel(), []);

  useEffect(() => {
    openIntentRef.current?.cancel();
  }, [objectListSignature]);

  const selectedObjectKey = useMemo(() => {
    if (!activeObject) return null;
    const matched = findMatchingDatabaseObject(objects, activeObject);
    return matched ? databaseObjectNodeKey(matched) : null;
  }, [activeObject, objects]);

  async function requestStructure(object: DbObject) {
    const identity = objectStructureKey(object);
    if (structureLoadingKey === identity) return;
    setStructureErrors((current) => {
      const next = new Set(current);
      next.delete(identity);
      return next;
    });
    const loaded = await onLoadStructure(object);
    if (!loaded) setStructureErrors((current) => new Set(current).add(identity));
  }

  function toggleKey(key: Key) {
    setExpandedKeys((current) => current.includes(key) ? current.filter((item) => item !== key) : [...current, key]);
  }

  function setObjectExpanded(object: DbObject, objectKey: Key, expanded: boolean) {
    setExpandedKeys((current) => expanded
      ? [...new Set([...keepOnlyObjectBranch(current, objectKey), objectKey])]
      : collapseObjectBranch(current, objectKey));
    if (expanded && object.columns.length === 0 && object.indexes.length === 0) void requestStructure(object);
  }

  function objectMenuItems(object: DbObject, expanded: boolean): MenuProps['items'] {
    const isView = object.type.toUpperCase().includes('VIEW');
    return [
      { key: 'detail', icon: <InfoCircleOutlined />, label: '查看对象详情' },
      { key: 'structure', icon: <UnorderedListOutlined />, label: expanded ? '收起字段和索引' : '展开字段和索引' },
      ...(!isView && onBackupTable ? [{ key: 'backup', icon: <CloudDownloadOutlined />, label: '备份此表' }] : [])
    ];
  }

  function renderMessageRow(key: string, content: ReactNode, icon?: ReactNode) {
    return (
      <div key={key} className="object-tree-row object-tree-message-row" style={rowStyle(2)} role="treeitem">
        <span className="object-tree-switcher-placeholder" />
        <TreeIcon>{icon}</TreeIcon>
        <span className="object-tree-row-main">{content}</span>
      </div>
    );
  }

  function renderStructure(object: DbObject, objectKey: string) {
    const identity = objectStructureKey(object);
    if (structureLoadingKey === identity) {
      return renderMessageRow(`${objectKey}:loading`, <span>正在加载结构…</span>, <Spin size="small" />);
    }
    if (structureErrors.has(identity)) {
      return renderMessageRow(`${objectKey}:error`, (
        <span className="object-tree-error-message">
          <span>结构加载失败</span>
          <Button type="link" size="small" onClick={() => void requestStructure(object)}>重试</Button>
        </span>
      ));
    }

    const rows: ReactNode[] = [];
    if (object.columns.length > 0) {
      const fieldsKey = `${objectKey}:columns`;
      const fieldsExpanded = expandedKeys.includes(fieldsKey);
      rows.push(
        <div key={fieldsKey} role="treeitem" aria-expanded={fieldsExpanded}>
          <div className="object-tree-row object-tree-structure-group-row" style={rowStyle(2)}>
            <TreeSwitcher expanded={fieldsExpanded} label={`${fieldsExpanded ? '收起' : '展开'}字段`} onClick={() => toggleKey(fieldsKey)} />
            <TreeIcon><UnorderedListOutlined /></TreeIcon>
            <button className="object-tree-row-main" type="button" onClick={() => toggleKey(fieldsKey)}>
              <span className="object-tree-row-label">字段</span>
              <span className="object-tree-count">{object.columns.length}</span>
            </button>
          </div>
          {fieldsExpanded && (
            <div role="group">
              {object.columns
                .slice()
                .sort((left, right) => (left.ordinalPosition ?? Number.MAX_SAFE_INTEGER) - (right.ordinalPosition ?? Number.MAX_SAFE_INTEGER))
                .map((column) => (
                  <div key={`${objectKey}:column:${encodeURIComponent(column.name)}`} className="object-tree-row object-tree-leaf-row" style={rowStyle(3)} role="treeitem">
                    <span className="object-tree-switcher-placeholder" />
                    <TreeIcon />
                    <span className="object-tree-row-main">
                      <span className="object-tree-leaf-name" title={column.name}>{column.name}</span>
                      <span className="object-tree-leaf-meta" title={column.type}>{column.type}</span>
                    </span>
                  </div>
                ))}
            </div>
          )}
        </div>
      );
    }

    const indexes = aggregateIndexes(object);
    if (indexes.length > 0) {
      const indexesKey = `${objectKey}:indexes`;
      const indexesExpanded = expandedKeys.includes(indexesKey);
      rows.push(
        <div key={indexesKey} role="treeitem" aria-expanded={indexesExpanded}>
          <div className="object-tree-row object-tree-structure-group-row" style={rowStyle(2)}>
            <TreeSwitcher expanded={indexesExpanded} label={`${indexesExpanded ? '收起' : '展开'}索引`} onClick={() => toggleKey(indexesKey)} />
            <TreeIcon><KeyOutlined /></TreeIcon>
            <button className="object-tree-row-main" type="button" onClick={() => toggleKey(indexesKey)}>
              <span className="object-tree-row-label">索引</span>
              <span className="object-tree-count">{indexes.length}</span>
            </button>
          </div>
          {indexesExpanded && (
            <div role="group">
              {indexes.map((index) => (
                <div key={`${objectKey}:index:${encodeURIComponent(index.name)}`} className="object-tree-row object-tree-leaf-row" style={rowStyle(3)} role="treeitem">
                  <span className="object-tree-switcher-placeholder" />
                  <TreeIcon><KeyOutlined /></TreeIcon>
                  <span className="object-tree-row-main">
                    <span className="object-tree-leaf-name" title={index.name}>{index.name}</span>
                    <span className="object-tree-leaf-meta" title={index.columns.join(', ')}>{index.unique ? '唯一 · ' : ''}{index.columns.join(', ')}</span>
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      );
    }

    return rows.length > 0 ? rows : renderMessageRow(`${objectKey}:empty`, <span>暂无字段或索引</span>);
  }

  if (objects.length === 0) {
    return <Empty className="object-tree-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyDescription} />;
  }

  return (
    <div className="object-tree-viewport object-tree" role="tree" aria-label="数据库对象">
      {groups.map((group, groupIndex) => {
        const groupKey = groupKeys[groupIndex];
        const groupExpanded = expandedKeys.includes(groupKey);
        return (
          <div className="object-tree-group" key={String(groupKey)} role="treeitem" aria-expanded={groupExpanded}>
            <div className="object-tree-row object-tree-group-row" style={rowStyle(0)}>
              <TreeSwitcher expanded={groupExpanded} label={`${groupExpanded ? '收起' : '展开'}${group.label}`} onClick={() => toggleKey(groupKey)} />
              <TreeIcon>{groupExpanded ? <FolderOpenOutlined /> : <FolderOutlined />}</TreeIcon>
              <button className="object-tree-row-main" type="button" onClick={() => toggleKey(groupKey)}>
                <span className="object-tree-row-label">{group.label}</span>
                <span className="object-tree-count">{group.objects.length}</span>
              </button>
            </div>
            {groupExpanded && (
              <div role="group">
                {group.objects.map((object) => {
                  const objectKey = databaseObjectNodeKey(object);
                  const objectExpanded = expandedKeys.includes(objectKey);
                  const selected = selectedObjectKey === objectKey;
                  const displayName = fullObjectName(object);
                  const isView = object.type.toUpperCase().includes('VIEW');
                  return (
                    <div className="object-tree-object" key={objectKey} role="treeitem" aria-expanded={objectExpanded} aria-selected={selected}>
                      <div className={`object-tree-row object-tree-object-row${selected ? ' is-selected' : ''}`} style={rowStyle(1)}>
                        <TreeSwitcher
                          expanded={objectExpanded}
                          label={`${objectExpanded ? '收起' : '展开'} ${displayName} 的结构`}
                          onClick={() => setObjectExpanded(object, objectKey, !objectExpanded)}
                        />
                        <TreeIcon>{objectIcon(object)}</TreeIcon>
                        <button
                          className="object-tree-row-main object-tree-object-trigger"
                          type="button"
                          title={displayName}
                          onClick={() => openIntentRef.current?.single(() => onOpenDetail(object))}
                          onDoubleClick={() => openIntentRef.current?.double(() => isView ? onOpenDetail(object) : onOpenTable(object))}
                        >
                          <span className="object-tree-object-name"><HighlightedName name={object.name} keyword={keyword} /></span>
                        </button>
                        <span className="object-tree-actions">
                          {!isView && (
                            <Tooltip title="打开表数据">
                              <Button
                                className="object-tree-action"
                                type="text"
                                size="small"
                                icon={<TableOutlined />}
                                aria-label={`打开 ${displayName} 的表数据`}
                                onClick={() => {
                                  openIntentRef.current?.cancel();
                                  onOpenTable(object);
                                }}
                              />
                            </Tooltip>
                          )}
                          <Dropdown
                            trigger={['click']}
                            menu={{
                              items: objectMenuItems(object, objectExpanded),
                              onClick: ({ key }) => {
                                openIntentRef.current?.cancel();
                                if (key === 'detail') onOpenDetail(object);
                                if (key === 'structure') setObjectExpanded(object, objectKey, !objectExpanded);
                                if (key === 'backup') onBackupTable?.(object);
                              }
                            }}
                          >
                            <Tooltip title="更多操作">
                              <Button className="object-tree-action" type="text" size="small" icon={<MoreOutlined />} aria-label={`${displayName} 更多操作`} />
                            </Tooltip>
                          </Dropdown>
                        </span>
                      </div>
                      {objectExpanded && <div role="group">{renderStructure(object, objectKey)}</div>}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
