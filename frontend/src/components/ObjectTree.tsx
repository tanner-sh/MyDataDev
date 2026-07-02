import { Button, Collapse, Empty, List, Space, Tag, Typography } from 'antd';
import type { DbObject } from '../types';
import { objectTypeLabel } from '../utils';

const { Text } = Typography;

export function ObjectTree({ objects, onOpenTable }: { objects: DbObject[]; onOpenTable: (object: DbObject) => void }) {
  if (objects.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未加载对象" />;
  }
  return (
    <Collapse
      size="small"
      className="object-collapse"
      items={objects.map((object) => ({
        key: `${object.schemaName}.${object.name}`,
        label: (
          <Space size={6} className="object-label">
            <Button
              type="link"
              size="small"
              className="object-open-button"
              disabled={object.type.toUpperCase().includes('VIEW')}
              onClick={(event) => {
                event.stopPropagation();
                onOpenTable(object);
              }}
            >
              {object.schemaName ? `${object.schemaName}.` : ''}{object.name}
            </Button>
            <Tag>{objectTypeLabel(object.type)}</Tag>
          </Space>
        ),
        children: (
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
