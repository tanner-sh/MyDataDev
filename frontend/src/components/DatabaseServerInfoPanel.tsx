import { Alert, Descriptions, Modal, Spin, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { api } from '../api';
import type { Connection } from '../types';

import type { CapabilityReport, ServerInfo, CapabilityFeature } from '../databaseCapabilities';
export function DatabaseServerInfoPanel({ connection, onClose }: { connection: Connection | null; onClose: () => void }) {
  const [info, setInfo] = useState<ServerInfo>();
  const [features, setFeatures] = useState<CapabilityFeature[]>([]);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setInfo(undefined); setError('');
    if (connection) void api<CapabilityReport>(`/connections/${connection.id}/capability-report`).then(value => { if (active) { setInfo(value.server); setFeatures(value.features); } })
      .catch(reason => { if (active) setError(reason instanceof Error ? reason.message : '读取服务端信息失败'); });
    return () => { active = false; };
  }, [connection]);
  return <Modal open={Boolean(connection)} title={`${connection?.name || ''} · 服务端信息`} footer={null} onCancel={onClose} destroyOnHidden>
    {error ? <Alert type="error" title={error} /> : !info ? <Spin /> : <>
      {info.warnings.map(message => <Alert key={message} type="warning" title={message} showIcon />)}
      <Descriptions column={1} size="small" items={[
        { key: 'product', label: '数据库', children: info.product },
        { key: 'version', label: '服务端版本', children: info.version },
        { key: 'driver', label: 'JDBC 驱动', children: info.driver },
        ...(connection?.dbType.startsWith('oceanbase-') ? [
          { key: 'tenant', label: '租户', children: info.tenantName ? `${info.tenantName} (${info.tenantId})` : '未验证' },
          { key: 'mode', label: '兼容模式', children: info.compatibilityMode || '未验证' }
        ] : [])
      ]} />
      <Table size="small" rowKey="key" pagination={false} dataSource={features} columns={[
        { title: '功能', dataIndex: 'label' },
        { title: '状态', dataIndex: 'status', render: value => <Tag color={value === 'UNAVAILABLE' ? 'default' : value === 'AVAILABLE' ? 'green' : 'gold'}>{value === 'UNAVAILABLE' ? '不可用' : value === 'AVAILABLE' ? '已识别' : '权限待验证'}</Tag> },
        { title: '原因', dataIndex: 'reason' }
      ]} />
    </>}
  </Modal>;
}
