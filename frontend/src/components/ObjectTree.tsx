import { useState } from 'react';
import { Button, Collapse, Empty, List, Space, Spin, Tag, Typography } from 'antd';
import type { DbObject } from '../types';
import { objectTypeLabel } from '../utils';

const { Text } = Typography;

function objectKey(object: DbObject) {
  return `${object.schemaName || ''}.${object.name}`;
}

export function ObjectTree({
  objects,
  structureLoadingKey,
  onLoadStructure,
  onOpenDetail,
  onOpenTable
}: {
  objects: DbObject[];
  structureLoadingKey?: string | null;
  onLoadStructure: (object: DbObject) => void;
  onOpenDetail: (object: DbObject) => void;
  onOpenTable: (object: DbObject) => void;
}) {
  const [activeKeys, setActiveKeys] = useState<string[]>([]);

  if (objects.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未加载对象" />;
  }
  return (
    <Collapse
      size="small"
      className="object-collapse"
      activeKey={activeKeys}
      onChange={(keys) => {
        const nextKeys = Array.isArray(keys) ? keys.map(String) : [String(keys)];
        setActiveKeys(nextKeys);
        nextKeys.forEach((key) => {
          const object = objects.find((item) => objectKey(item) === key);
          if (object && object.columns.length === 0 && object.indexes.length === 0) {
            onLoadStructure(object);
          }
        });
      }}
      items={objects.map((object) => ({
        key: objectKey(object),
        label: (
          <Space size={6} className="object-label">
            <Button
              type="link"
              size="small"
              className="object-open-button"
              onClick={(event) => {
                event.stopPropagation();
                onOpenDetail(object);
              }}
            >
              {object.schemaName ? `${object.schemaName}.` : ''}{object.name}
            </Button>
            <Tag>{objectTypeLabel(object.type)}</Tag>
            {!object.type.toUpperCase().includes('VIEW') && (
              <Button
                type="link"
                size="small"
                className="object-data-button"
                onClick={(event) => {
                  event.stopPropagation();
                  onOpenTable(object);
                }}
              >
                数据
              </Button>
            )}
          </Space>
        ),
        children: structureLoadingKey === objectKey(object) ? (
          <div className="object-structure-loading">
            <Spin size="small" />
            <Text type="secondary">正在加载字段...</Text>
          </div>
        ) : object.columns.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无字段信息" />
        ) : (
          <List
            size="small"
            dataSource={object.columns}
            renderItem={(column) => (
              <List.Item className="column-item">
                <Text>{column.name}</Text>
                <Text type="secondary">{column.type}</Text>
              </List.Item>
            )}
          />
        )
      }))}
    />
  );
}
