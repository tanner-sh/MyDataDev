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
import { memo, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, Key, ReactNode, UIEvent } from 'react';
import {
  collapseObjectBranch,
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
  hasMore?: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
  onLoadStructure: (object: DbObject) => Promise<DbObject | null>;
  onOpenDetail: (object: DbObject) => void;
  onOpenTable: (object: DbObject) => void;
  onBackupTable?: (object: DbObject) => void;
};

type AggregatedIndex = { name: string; columns: string[]; unique: boolean };
type FlatRow =
  | { id: string; kind: 'group'; level: 0; key: Key; label: string; count: number; expanded: boolean }
  | { id: string; kind: 'object'; level: 1; key: Key; object: DbObject; expanded: boolean; selected: boolean }
  | { id: string; kind: 'structure-group'; level: 2; key: Key; object: DbObject; structureKind: 'columns' | 'indexes'; label: string; count: number; expanded: boolean }
  | { id: string; kind: 'column'; level: 3; object: DbObject; name: string; meta: string }
  | { id: string; kind: 'index'; level: 3; object: DbObject; name: string; meta: string }
  | { id: string; kind: 'message'; level: 2; object: DbObject; message: 'loading' | 'error' | 'empty' };

const ROW_HEIGHT = 30;
const OVERSCAN = 10;

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
  object.indexes
    .slice()
    .sort((left, right) => (left.ordinalPosition || 0) - (right.ordinalPosition || 0))
    .forEach((index) => {
      const identity = index.name || `${index.columnName}-index`;
      const existing = indexes.get(identity);
      if (existing) {
        if (!existing.columns.includes(index.columnName)) existing.columns.push(index.columnName);
      } else {
        indexes.set(identity, { name: index.name || '未命名索引', columns: [index.columnName], unique: index.unique });
      }
    });
  return [...indexes.values()].sort((left, right) => left.name.localeCompare(right.name, 'zh-CN', { numeric: true, sensitivity: 'base' }));
}

function HighlightedName({ name, keyword }: { name: string; keyword?: string }) {
  const normalizedKeyword = keyword?.trim().toLocaleLowerCase();
  if (!normalizedKeyword) return <>{name}</>;
  const start = name.toLocaleLowerCase().indexOf(normalizedKeyword);
  if (start < 0) return <>{name}</>;
  return <>{name.slice(0, start)}<mark className="object-tree-match">{name.slice(start, start + normalizedKeyword.length)}</mark>{name.slice(start + normalizedKeyword.length)}</>;
}

function TreeSwitcher({ expanded, label, onClick }: { expanded: boolean; label: string; onClick: () => void }) {
  return <button className="object-tree-switcher" type="button" aria-label={label} onClick={onClick}>{expanded ? <CaretDownOutlined /> : <CaretRightOutlined />}</button>;
}

function TreeIcon({ children }: { children?: ReactNode }) {
  return <span className="object-tree-row-icon" aria-hidden="true">{children}</span>;
}

export const ObjectTree = memo(function ObjectTree({
  objects,
  activeObject,
  keyword,
  emptyDescription = '当前 Schema 暂无数据库对象',
  structureLoadingKey,
  hasMore,
  loadingMore,
  onLoadMore,
  onLoadStructure,
  onOpenDetail,
  onOpenTable,
  onBackupTable
}: ObjectTreeProps) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const groups = useMemo(() => groupDatabaseObjects(objects), [objects]);
  const groupKeys = useMemo(() => groups.map((group) => treeNodeKey('object-type', group.objects[0]?.schemaName || '', group.key)), [groups]);
  const [expandedKeys, setExpandedKeys] = useState<Key[]>(groupKeys);
  const [structureErrors, setStructureErrors] = useState<Set<string>>(() => new Set());
  const [structureLoadingKeys, setStructureLoadingKeys] = useState<Set<string>>(() => new Set());
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(400);
  const scrollFrameRef = useRef<number | null>(null);
  const latestScrollRef = useRef({ top: 0, height: 0, clientHeight: 0 });
  const loadMoreRequestedRef = useRef(false);

  useEffect(() => {
    setExpandedKeys((current) => [...new Set([...groupKeys, ...current])]);
  }, [groupKeys.join('|')]);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const update = () => setViewportHeight(Math.max(ROW_HEIGHT, viewport.clientHeight));
    update();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    observer?.observe(viewport);
    return () => observer?.disconnect();
  }, []);

  useEffect(() => () => {
    if (scrollFrameRef.current != null) cancelAnimationFrame(scrollFrameRef.current);
  }, []);

  useEffect(() => {
    if (!loadingMore) loadMoreRequestedRef.current = false;
  }, [loadingMore, objects.length]);

  const selectedObjectKey = useMemo(() => {
    if (!activeObject) return null;
    const matched = findMatchingDatabaseObject(objects, activeObject);
    return matched ? databaseObjectNodeKey(matched) : null;
  }, [activeObject, objects]);

  const rows = useMemo(() => flattenRows(groups, groupKeys, expandedKeys, selectedObjectKey, structureLoadingKey, structureLoadingKeys, structureErrors), [
    expandedKeys,
    groupKeys,
    groups,
    selectedObjectKey,
    structureErrors,
    structureLoadingKey,
    structureLoadingKeys
  ]);

  const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
  const end = Math.min(rows.length, Math.ceil((scrollTop + viewportHeight) / ROW_HEIGHT) + OVERSCAN);
  const visibleRows = rows.slice(start, end);

  async function requestStructure(object: DbObject) {
    const identity = objectStructureKey(object);
    if (structureLoadingKeys.has(identity)) return;
    setStructureLoadingKeys((current) => new Set(current).add(identity));
    setStructureErrors((current) => {
      const next = new Set(current);
      next.delete(identity);
      return next;
    });
    try {
      const loaded = await onLoadStructure(object);
      if (!loaded) setStructureErrors((current) => new Set(current).add(identity));
    } finally {
      setStructureLoadingKeys((current) => {
        const next = new Set(current);
        next.delete(identity);
        return next;
      });
    }
  }

  function toggleKey(key: Key) {
    setExpandedKeys((current) => current.includes(key) ? current.filter((item) => item !== key) : [...current, key]);
  }

  function setObjectExpanded(object: DbObject, objectKey: Key, expanded: boolean) {
    setExpandedKeys((current) => expanded ? [...new Set([...keepOnlyObjectBranch(current, objectKey), objectKey])] : collapseObjectBranch(current, objectKey));
    if (expanded && object.columns.length === 0 && object.indexes.length === 0) void requestStructure(object);
  }

  function handleScroll(event: UIEvent<HTMLDivElement>) {
    const viewport = event.currentTarget;
    latestScrollRef.current = { top: viewport.scrollTop, height: viewport.scrollHeight, clientHeight: viewport.clientHeight };
    if (scrollFrameRef.current != null) return;
    scrollFrameRef.current = requestAnimationFrame(() => {
      scrollFrameRef.current = null;
      const latest = latestScrollRef.current;
      setScrollTop(latest.top);
      if (hasMore && !loadingMore && onLoadMore && !loadMoreRequestedRef.current
          && latest.height - latest.top - latest.clientHeight < ROW_HEIGHT * 8) {
        loadMoreRequestedRef.current = true;
        onLoadMore();
      }
    });
  }

  function objectMenuItems(object: DbObject, expanded: boolean): MenuProps['items'] {
    const isView = object.type.toUpperCase().includes('VIEW');
    return [
      { key: 'detail', icon: <InfoCircleOutlined />, label: '查看对象详情' },
      { key: 'structure', icon: <UnorderedListOutlined />, label: expanded ? '收起字段和索引' : '展开字段和索引' },
      ...(!isView && onBackupTable ? [{ key: 'backup', icon: <CloudDownloadOutlined />, label: '备份此表' }] : [])
    ];
  }

  if (objects.length === 0) return <Empty className="object-tree-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyDescription} />;

  return (
    <div ref={viewportRef} className="object-tree-viewport object-tree object-tree-virtual" role="tree" aria-label="数据库对象" onScroll={handleScroll}>
      <div className="object-tree-virtual-spacer" style={{ height: rows.length * ROW_HEIGHT + (loadingMore ? ROW_HEIGHT : 0) }}>
        {visibleRows.map((row, visibleIndex) => {
          const index = start + visibleIndex;
          return (
            <div className="object-tree-virtual-row" key={row.id} style={{ transform: `translateY(${index * ROW_HEIGHT}px)` }}>
              {renderRow(row)}
            </div>
          );
        })}
        {loadingMore && <div className="object-tree-virtual-row object-tree-load-more" style={{ transform: `translateY(${rows.length * ROW_HEIGHT}px)` }}><Spin size="small" /> 正在加载更多对象…</div>}
      </div>
    </div>
  );

  function renderRow(row: FlatRow) {
    if (row.kind === 'group') {
      return (
        <div className="object-tree-row object-tree-group-row" style={rowStyle(row.level)} role="treeitem" aria-expanded={row.expanded}>
          <TreeSwitcher expanded={row.expanded} label={`${row.expanded ? '收起' : '展开'}${row.label}`} onClick={() => toggleKey(row.key)} />
          <TreeIcon>{row.expanded ? <FolderOpenOutlined /> : <FolderOutlined />}</TreeIcon>
          <button className="object-tree-row-main" type="button" onClick={() => toggleKey(row.key)}><span className="object-tree-row-label">{row.label}</span><span className="object-tree-count">{row.count}</span></button>
        </div>
      );
    }
    if (row.kind === 'object') {
      const displayName = fullObjectName(row.object);
      const isView = row.object.type.toUpperCase().includes('VIEW');
      return (
        <div className={`object-tree-row object-tree-object-row${row.selected ? ' is-selected' : ''}`} style={rowStyle(row.level)} role="treeitem" aria-expanded={row.expanded} aria-selected={row.selected}>
          <TreeSwitcher expanded={row.expanded} label={`${row.expanded ? '收起' : '展开'} ${displayName} 的结构`} onClick={() => setObjectExpanded(row.object, row.key, !row.expanded)} />
          <TreeIcon>{objectIcon(row.object)}</TreeIcon>
          <button className="object-tree-row-main object-tree-object-trigger" type="button" title={`${displayName} · 查看详情`} onClick={() => onOpenDetail(row.object)}>
            <span className="object-tree-object-name"><HighlightedName name={row.object.name} keyword={keyword} /></span>
          </button>
          <span className="object-tree-actions">
            {!isView && <Tooltip title="打开表数据"><Button className="object-tree-action" type="text" size="small" icon={<TableOutlined />} aria-label={`打开 ${displayName} 的表数据`} onClick={() => onOpenTable(row.object)} /></Tooltip>}
            <Dropdown trigger={['click']} menu={{ items: objectMenuItems(row.object, row.expanded), onClick: ({ key }) => { if (key === 'detail') onOpenDetail(row.object); if (key === 'structure') setObjectExpanded(row.object, row.key, !row.expanded); if (key === 'backup') onBackupTable?.(row.object); } }}>
              <Tooltip title="更多操作"><Button className="object-tree-action" type="text" size="small" icon={<MoreOutlined />} aria-label={`${displayName} 更多操作`} /></Tooltip>
            </Dropdown>
          </span>
        </div>
      );
    }
    if (row.kind === 'structure-group') {
      return (
        <div className="object-tree-row object-tree-structure-group-row" style={rowStyle(row.level)} role="treeitem" aria-expanded={row.expanded}>
          <TreeSwitcher expanded={row.expanded} label={`${row.expanded ? '收起' : '展开'}${row.label}`} onClick={() => toggleKey(row.key)} />
          <TreeIcon>{row.structureKind === 'indexes' ? <KeyOutlined /> : <UnorderedListOutlined />}</TreeIcon>
          <button className="object-tree-row-main" type="button" onClick={() => toggleKey(row.key)}><span className="object-tree-row-label">{row.label}</span><span className="object-tree-count">{row.count}</span></button>
        </div>
      );
    }
    if (row.kind === 'message') {
      return (
        <div className="object-tree-row object-tree-message-row" style={rowStyle(row.level)} role="treeitem">
          <span className="object-tree-switcher-placeholder" /><TreeIcon>{row.message === 'loading' ? <Spin size="small" /> : undefined}</TreeIcon>
          <span className="object-tree-row-main">{row.message === 'loading' ? '正在加载结构…' : row.message === 'empty' ? '暂无字段或索引' : <span className="object-tree-error-message"><span>结构加载失败</span><Button type="link" size="small" onClick={() => void requestStructure(row.object)}>重试</Button></span>}</span>
        </div>
      );
    }
    return (
      <div className="object-tree-row object-tree-leaf-row" style={rowStyle(row.level)} role="treeitem">
        <span className="object-tree-switcher-placeholder" /><TreeIcon>{row.kind === 'index' ? <KeyOutlined /> : undefined}</TreeIcon>
        <span className="object-tree-row-main"><span className="object-tree-leaf-name" title={row.name}>{row.name}</span><span className="object-tree-leaf-meta" title={row.meta}>{row.meta}</span></span>
      </div>
    );
  }
});

function flattenRows(
  groups: ReturnType<typeof groupDatabaseObjects>,
  groupKeys: Key[],
  expandedKeys: Key[],
  selectedObjectKey: string | null,
  structureLoadingKey: string | null | undefined,
  structureLoadingKeys: Set<string>,
  structureErrors: Set<string>
) {
  const rows: FlatRow[] = [];
  groups.forEach((group, groupIndex) => {
    const groupKey = groupKeys[groupIndex];
    const groupExpanded = expandedKeys.includes(groupKey);
    rows.push({ id: String(groupKey), kind: 'group', level: 0, key: groupKey, label: group.label, count: group.objects.length, expanded: groupExpanded });
    if (!groupExpanded) return;
    group.objects.forEach((object) => {
      const objectKey = databaseObjectNodeKey(object);
      const objectExpanded = expandedKeys.includes(objectKey);
      rows.push({ id: objectKey, kind: 'object', level: 1, key: objectKey, object, expanded: objectExpanded, selected: selectedObjectKey === objectKey });
      if (!objectExpanded) return;
      const identity = objectStructureKey(object);
      if (structureLoadingKey === identity || structureLoadingKeys.has(identity)) {
        rows.push({ id: `${objectKey}:loading`, kind: 'message', level: 2, object, message: 'loading' });
        return;
      }
      if (structureErrors.has(identity)) {
        rows.push({ id: `${objectKey}:error`, kind: 'message', level: 2, object, message: 'error' });
        return;
      }
      let hasStructure = false;
      if (object.columns.length > 0) {
        hasStructure = true;
        const key = `${objectKey}:columns`;
        const expanded = expandedKeys.includes(key);
        rows.push({ id: key, kind: 'structure-group', level: 2, key, object, structureKind: 'columns', label: '字段', count: object.columns.length, expanded });
        if (expanded) object.columns.slice().sort((left, right) => (left.ordinalPosition ?? Number.MAX_SAFE_INTEGER) - (right.ordinalPosition ?? Number.MAX_SAFE_INTEGER)).forEach((column) => rows.push({ id: `${objectKey}:column:${encodeURIComponent(column.name)}`, kind: 'column', level: 3, object, name: column.name, meta: column.type }));
      }
      const indexes = aggregateIndexes(object);
      if (indexes.length > 0) {
        hasStructure = true;
        const key = `${objectKey}:indexes`;
        const expanded = expandedKeys.includes(key);
        rows.push({ id: key, kind: 'structure-group', level: 2, key, object, structureKind: 'indexes', label: '索引', count: indexes.length, expanded });
        if (expanded) indexes.forEach((index) => rows.push({ id: `${objectKey}:index:${encodeURIComponent(index.name)}`, kind: 'index', level: 3, object, name: index.name, meta: `${index.unique ? '唯一 · ' : ''}${index.columns.join(', ')}` }));
      }
      if (!hasStructure) rows.push({ id: `${objectKey}:empty`, kind: 'message', level: 2, object, message: 'empty' });
    });
  });
  return rows;
}
