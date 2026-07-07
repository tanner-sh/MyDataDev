import { Button, Checkbox, Form, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import { CopyOutlined, DeleteOutlined, EditOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons';
import { DB_TYPE_OPTIONS, ENVIRONMENT_OPTIONS } from '../constants';
import type { Connection, ConnectionForm } from '../types';
import { dbTypeLabel, environmentLabel, normalizeEnvironment } from '../utils';

const { Text } = Typography;

export function ConnectionFormPanel({ form, selected, editing, loading, onChange, onDbTypeChange, onReset, onEdit, onDuplicate, onDelete, onTest, onSave }: {
  form: ConnectionForm;
  selected: Connection | null;
  editing: boolean;
  loading: boolean;
  onChange: (form: ConnectionForm) => void;
  onDbTypeChange: (dbType: string) => void;
  onReset: () => void;
  onEdit: (connection: Connection) => void;
  onDuplicate: (connection: Connection) => void;
  onDelete: (connection: Connection) => void;
  onTest: () => void;
  onSave: () => void;
}) {
  return (
    <section className="inspector-section">
      <div className="inspector-section-header">
        <Text strong>{editing ? '编辑连接' : '新建连接'}</Text>
        <Button size="small" icon={<PlusOutlined />} onClick={onReset}>新建</Button>
      </div>
      {selected && (
        <div className="connection-summary">
          <div className="connection-summary-title">
            <Text strong className="ellipsis-text">{selected.name}</Text>
            {selected.readonly && <Tag color="orange">只读</Tag>}
          </div>
          <Space size={4} wrap>
            <Tag color="blue">{dbTypeLabel(selected.dbType)}</Tag>
            <Tag>{environmentLabel(selected.environment)}</Tag>
          </Space>
          <Text type="secondary" className="ellipsis-text connection-url">{selected.jdbcUrl}</Text>
          <div className="connection-summary-actions">
            <Button size="small" icon={<EditOutlined />} onClick={() => onEdit(selected)}>编辑</Button>
            <Button size="small" icon={<CopyOutlined />} onClick={() => onDuplicate(selected)}>复制</Button>
            <Popconfirm
              title="删除连接"
              description="确定删除该连接吗？有关联备份任务的连接会被后端拒绝删除。"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => onDelete(selected)}
            >
              <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          </div>
        </div>
      )}
      <Form layout="vertical" size="small" className="compact-form">
        <Form.Item label="连接名称">
          <Input value={form.name} onChange={(event) => onChange({ ...form, name: event.target.value })} />
        </Form.Item>
        <Form.Item label="数据库类型">
          <Select value={form.dbType} options={DB_TYPE_OPTIONS.map(({ value, label }) => ({ value, label }))} onChange={onDbTypeChange} />
        </Form.Item>
        <Form.Item label="数据库地址">
          <Input value={form.jdbcUrl} onChange={(event) => onChange({ ...form, jdbcUrl: event.target.value })} />
        </Form.Item>
        <Form.Item label="用户名">
          <Input value={form.username} onChange={(event) => onChange({ ...form, username: event.target.value })} />
        </Form.Item>
        <Form.Item label="密码">
          <Input.Password value={form.password} onChange={(event) => onChange({ ...form, password: event.target.value })} />
        </Form.Item>
        <Form.Item label="环境">
          <Select value={normalizeEnvironment(form.environment)} options={ENVIRONMENT_OPTIONS} onChange={(value) => onChange({ ...form, environment: value })} />
        </Form.Item>
        <Form.Item>
          <Checkbox checked={form.readonly} onChange={(event) => onChange({ ...form, readonly: event.target.checked })}>只读连接</Checkbox>
        </Form.Item>
        {form.dbType === 'oracle' && (
          <Text type="secondary" className="form-hint-text">
            Oracle 示例：Service Name 使用 jdbc:oracle:thin:@//localhost:1521/ORCLPDB1；SID 使用 jdbc:oracle:thin:@localhost:1521:ORCL。
          </Text>
        )}
        {editing && <Text type="secondary" className="form-hint-text">编辑已有连接时，密码为 ****** 或留空都表示沿用原密码。</Text>}
        <Space className="form-actions" size={8}>
          <Button block onClick={onTest} loading={loading}>测试连接</Button>
          <Button block type="primary" icon={<SaveOutlined />} onClick={onSave} loading={loading}>{editing ? '保存修改' : '保存连接'}</Button>
        </Space>
      </Form>
    </section>
  );
}
