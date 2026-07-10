import { EyeOutlined, InfoCircleOutlined, KeyOutlined, ProfileOutlined, TableOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Button, Empty, Space, Spin, Tooltip, Tree, Typography } from 'antd';
import { useLayoutEffect, useRef, useState } from 'react';
import type { Key, ReactNode } from 'react';
import type { DbObject } from '../types';
import { objectTypeLabel } from '../utils';

const { Text } = Typography;

type NodeKind = 'object-type' | 'object' | 'structure-group' | 'structure-item' | 'message';

type ObjectTreeNode = {
  key: Key;
  title: ReactNode;
  kind: NodeKind;
  object?: DbObject;
  children?: ObjectTreeNode[];
  icon?: ReactNode;
  isLeaf?: boolean;
  selectable?: boolean;
};

type ObjectGroup = {
  key: string;
  label: string;
  objects: DbObject[];
};

function structureKey(object: DbObject) {
  return `${object.schemaName || ''}.${object.name}`;
}

function nodeKey(kind: string, ...parts: string[]) {
  return [kind, ...parts.map((part) => encodeURIComponent(part))].join(':');
}

function objectGroup(type: string) {
  const normalized = type.toUpperCase();
  if (normalized.includes('VIEW')) return { key: 'VIEW', label: '视图' };
  if (normalized.includes('TABLE')) return { key: 'TABLE', label: '表' };
  return { key: normalized || 'OTHER', label: objectTypeLabel(type) || '其他对象' };
}

function groupIcon(groupKey: string) {
  return groupKey === 'VIEW' ? <EyeOutlined /> : groupKey === 'TABLE' ? <TableOutlined /> : <ProfileOutlined />;
}

function objectIcon(object: DbObject) {
  return object.type.toUpperCase().includes('VIEW') ? <EyeOutlined /> : <TableOutlined />;
}

function fullObjectName(object: DbObject) {
  return object.schemaName ? `${object.schemaName}.${object.name}` : object.name;
}

function buildIndexNodes(object: DbObject, objectNodeKey: string): ObjectTreeNode[] {
  const indexes = new Map<string, { name: string; columns: string[]; unique: boolean }>();

  object.indexes.forEach((index) => {
    const identity = index.name || `${index.columnName}-index`;
    const existing = indexes.get(identity);
    if (existing) {
      if (!existing.columns.includes(index.columnName)) existing.columns.push(index.columnName);
      return;
    }
    indexes.set(identity, { name: index.name || '未命名索引', columns: [index.columnName], unique: index.unique });
  });

  return Array.from(indexes.values())
    .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN', { numeric: true, sensitivity: 'base' }))
    .map((index) => ({
      key: `${objectNodeKey}:index:${encodeURIComponent(index.name)}`,
      kind: 'structure-item',
      isLeaf: true,
      selectable: false,
      icon: <KeyOutlined />,
      title: (
        <span className="object-tree-index" style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <Text ellipsis={{ tooltip: index.name }} style={{ minWidth: 0 }}>{index.name}</Text>
          <Text type="secondary" ellipsis={{ tooltip: index.columns.join(', ') }} style={{ minWidth: 0 }}>
            {index.unique ? '唯一 · ' : ''}{index.columns.join(', ')}
          </Text>
        </span>
      )
    }));
}

function buildStructureNodes(object: DbObject, objectNodeKey: string, loading: boolean): ObjectTreeNode[] {
  if (loading) {
    return [{
      key: `${objectNodeKey}:loading`,
      kind: 'message',
      isLeaf: true,
      selectable: false,
      title: (
        <Space size={6} className="object-tree-structure-message">
          <Spin size="small" />
          <Text type="secondary">正在加载结构...</Text>
        </Space>
      )
    }];
  }

  const children: ObjectTreeNode[] = [];
  if (object.columns.length > 0) {
    children.push({
      key: `${objectNodeKey}:columns`,
      kind: 'structure-group',
      selectable: false,
      icon: <UnorderedListOutlined />,
      title: (
        <Space size={6} className="object-tree-structure-title">
          <span>字段</span>
          <Text type="secondary">{object.columns.length}</Text>
        </Space>
      ),
      children: object.columns
        .slice()
        .sort((left, right) => (left.ordinalPosition ?? Number.MAX_SAFE_INTEGER) - (right.ordinalPosition ?? Number.MAX_SAFE_INTEGER))
        .map((column) => ({
          key: `${objectNodeKey}:column:${encodeURIComponent(column.name)}`,
          kind: 'structure-item',
          isLeaf: true,
          selectable: false,
          title: (
            <span className="object-tree-column" style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
              <Text ellipsis={{ tooltip: column.name }} style={{ flex: '1 1 auto', minWidth: 0 }}>{column.name}</Text>
              <Text className="object-tree-column-type" type="secondary" ellipsis={{ tooltip: column.type }} style={{ flex: '0 1 auto', minWidth: 0 }}>
                {column.type}
              </Text>
            </span>
          )
        }))
    });
  }

  const indexNodes = buildIndexNodes(object, objectNodeKey);
  if (indexNodes.length > 0) {
    children.push({
      key: `${objectNodeKey}:indexes`,
      kind: 'structure-group',
      selectable: false,
      icon: <KeyOutlined />,
      title: (
        <Space size={6} className="object-tree-structure-title">
          <span>索引</span>
          <Text type="secondary">{indexNodes.length}</Text>
        </Space>
      ),
      children: indexNodes
    });
  }

  if (children.length > 0) return children;
  return [{
    key: `${objectNodeKey}:empty`,
    kind: 'message',
    isLeaf: true,
    selectable: false,
    title: <Text className="object-tree-structure-message" type="secondary">暂无字段或索引</Text>
  }];
}

export function ObjectTree({
  objects,
  emptyDescription = '当前 Schema 暂无数据库对象',
  structureLoadingKey,
  onLoadStructure,
  onOpenDetail,
  onOpenTable
}: {
  objects: DbObject[];
  emptyDescription?: string;
  structureLoadingKey?: string | null;
  onLoadStructure: (object: DbObject) => void;
  onOpenDetail: (object: DbObject) => void;
  onOpenTable: (object: DbObject) => void;
}) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const [treeHeight, setTreeHeight] = useState(320);

  useLayoutEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport || typeof ResizeObserver === 'undefined') return;
    const updateHeight = () => setTreeHeight(Math.max(160, Math.floor(viewport.clientHeight - 8)));
    updateHeight();
    const observer = new ResizeObserver(updateHeight);
    observer.observe(viewport);
    return () => observer.disconnect();
  }, [objects.length === 0]);

  if (objects.length === 0) {
    return <Empty className="object-tree-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyDescription} />;
  }

  const groups = new Map<string, ObjectGroup>();
  objects.forEach((object) => {
    const group = objectGroup(object.type);
    const current = groups.get(group.key) || { ...group, objects: [] };
    current.objects.push(object);
    groups.set(group.key, current);
  });

  const defaultExpandedKeys: Key[] = [];
  const treeData: ObjectTreeNode[] = Array.from(groups.values())
    .sort((left, right) => {
      const order = (key: string) => key === 'TABLE' ? 0 : key === 'VIEW' ? 1 : 2;
      return order(left.key) - order(right.key) || left.label.localeCompare(right.label, 'zh-CN');
    })
    .map((group) => {
      const schemaName = group.objects[0]?.schemaName || '';
      const groupNodeKey = nodeKey('object-type', schemaName, group.key);
      defaultExpandedKeys.push(groupNodeKey);
      return {
        key: groupNodeKey,
        kind: 'object-type',
        selectable: false,
        icon: groupIcon(group.key),
        title: (
          <Space size={6} className="object-tree-group-title">
            <span>{group.label}</span>
            <Text type="secondary">{group.objects.length}</Text>
          </Space>
        ),
        children: group.objects.map((object) => {
          const objectSchema = object.schemaName || schemaName;
          const objectNodeKey = nodeKey('object', objectSchema, group.key, object.type, object.name);
          const displayName = fullObjectName(object);
          const isView = object.type.toUpperCase().includes('VIEW');
          const loading = structureLoadingKey === structureKey(object);
          return {
            key: objectNodeKey,
            kind: 'object',
            object,
            selectable: false,
            icon: objectIcon(object),
            title: (
              <span className="object-tree-object-title" style={{ display: 'flex', alignItems: 'center', width: '100%', minWidth: 0, gap: 4 }}>
                <Text className="object-tree-object-name" ellipsis={{ tooltip: displayName }} style={{ flex: '1 1 auto', minWidth: 0 }}>
                  {object.name}
                </Text>
                <span className="object-tree-actions" style={{ display: 'inline-flex', flex: '0 0 auto' }}>
                  <Tooltip title="查看对象详情">
                    <Button
                      className="object-tree-action"
                      type="text"
                      size="small"
                      icon={<InfoCircleOutlined />}
                      aria-label={`查看 ${displayName} 的对象详情`}
                      style={{ width: 32, height: 32, padding: 0 }}
                      onMouseDown={(event) => event.stopPropagation()}
                      onClick={(event) => {
                        event.stopPropagation();
                        onOpenDetail(object);
                      }}
                    />
                  </Tooltip>
                  {!isView && (
                    <Tooltip title="打开表数据">
                      <Button
                        className="object-tree-action"
                        type="text"
                        size="small"
                        icon={<TableOutlined />}
                        aria-label={`打开 ${displayName} 的表数据`}
                        style={{ width: 32, height: 32, padding: 0 }}
                        onMouseDown={(event) => event.stopPropagation()}
                        onClick={(event) => {
                          event.stopPropagation();
                          onOpenTable(object);
                        }}
                      />
                    </Tooltip>
                  )}
                </span>
              </span>
            ),
            children: buildStructureNodes(object, objectNodeKey, loading)
          } satisfies ObjectTreeNode;
        })
      } satisfies ObjectTreeNode;
    });

  return (
    <div ref={viewportRef} className="object-tree-viewport">
      <Tree<ObjectTreeNode>
        className="object-tree"
        treeData={treeData}
        defaultExpandedKeys={defaultExpandedKeys}
        blockNode
        showIcon
        showLine={{ showLeafIcon: false }}
        selectable={false}
        virtual
        height={treeHeight}
        styles={{
          item: { minHeight: 32 },
          itemTitle: { flex: 1, minWidth: 0 }
        }}
        onExpand={(_, info) => {
          const object = info.node.object;
          if (!info.expanded || info.node.kind !== 'object' || !object) return;
          if (object.columns.length === 0 && object.indexes.length === 0 && structureLoadingKey !== structureKey(object)) {
            onLoadStructure(object);
          }
        }}
      />
    </div>
  );
}
