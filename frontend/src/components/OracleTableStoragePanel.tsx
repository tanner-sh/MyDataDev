import { Alert, Button, Descriptions, Space, Spin, Table } from 'antd';
import { useEffect, useState } from 'react';
import { api } from '../api';
import type { ObjectDetail } from '../types';
type Partition = { name: string; tablespace?: string; estimatedRows?: string; lastAnalyzed?: string };
type Storage = { tablespace?: string; partitioned: boolean; estimatedRows?: string; lastAnalyzed?: string;
  tableBytes?: string; indexBytes?: string; lobBytes?: string; partitions: Partition[]; partitionsTruncated: boolean; warnings: string[] };
export function OracleTableStoragePanel({ connectionId, detail, active }: { connectionId?: number; detail: ObjectDetail; active: boolean }) {
  const [info, setInfo] = useState<Storage>();
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let live = true;
    if (active && connectionId) {
      setError(''); setInfo(undefined);
      void api<Storage>(`/metadata/${connectionId}/oracle-storage?${new URLSearchParams({ schema: detail.schemaName || '', table: detail.name })}`)
        .then(value => { if (live) setInfo(value); }).catch(reason => { if (live) setError(reason.message); });
    }
    return () => { live = false; };
  }, [active, connectionId, detail.schemaName, detail.name, revision]);
  return <Space orientation="vertical" className="full-width" size={12}>
    <Button onClick={() => setRevision(value => value + 1)}>刷新存储信息</Button>
    {error && <Alert type="error" title={error} showIcon />}
    {!info && !error && <Spin />}
    {info && <>
      <Alert type="info" showIcon title="行数来自优化器统计，可能过期；容量是已分配段空间，不等于实际数据大小。此页面仅查询，不修改分区或表空间。" />
      {info.warnings.map(warning => <Alert key={warning} type="warning" title={warning} showIcon />)}
      <Descriptions column={3} size="small" items={[
        { key: 'ts', label: '表空间', children: info.tablespace || (info.partitioned ? '见各分区' : '未知') },
        { key: 'rows', label: '估算行数', children: info.estimatedRows ?? '未收集' },
        { key: 'analyzed', label: '统计时间', children: info.lastAnalyzed ?? '未收集' },
        { key: 'data', label: '表段字节', children: info.tableBytes ?? '未知' },
        { key: 'index', label: '索引段字节', children: info.indexBytes ?? '未知' },
        { key: 'lob', label: 'LOB 及其索引字节', children: info.lobBytes ?? '未知' }
      ]} />
      {info.partitioned ? <Table<Partition> size="small" rowKey="name" dataSource={info.partitions} pagination={{ pageSize: 20 }} columns={[
        { title: '分区', dataIndex: 'name' }, { title: '表空间', dataIndex: 'tablespace' },
        { title: '估算行数', dataIndex: 'estimatedRows', render: value => value ?? '未收集' },
        { title: '统计时间', dataIndex: 'lastAnalyzed', render: value => value ?? '未收集' }
      ]} /> : <span>非分区表</span>}
      {info.partitionsTruncated && <Alert type="warning" title="仅展示前 500 个分区；容量统计仍覆盖全部段。" />}
    </>}
  </Space>;
}
