import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Input, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { CheckOutlined, CloseOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '../api';
import { localizeError, formatDateTime } from '../utils';
import { hasConnectionPermission } from '../accessControl';
import { filterRelations, invalidateRelationKnowledge, manualRelationProblem, pairKey, relationText } from '../tableRelations';
import type { Connection, MinedTableRelation, TableRelation, TableRelationPair, TableRelationSource, TableRelationStatus, TableRelationsResponse } from '../types';

const { Text, Paragraph } = Typography;

const SOURCE_LABELS: Record<TableRelationSource, string> = { HISTORY: '执行历史', NAMING: '命名推断', MANUAL: '手工添加' };
const EMPTY_PAIR: TableRelationPair = { leftTable: '', leftColumn: '', rightTable: '', rightColumn: '' };

function formatTime(value?: string | null) {
  return formatDateTime(value) || '—';
}

/**
 * 表关系：JOIN 补全的知识来源。
 *
 * <p>候选来自这条连接上所有人最近成功执行的 SQL（服务端只返回「表.列 = 表.列」与次数，看不到别人的 SQL）。
 * 确认的会排在补全最前面，否决的不再提示 —— 包括命名推断推出来的错误条件：在下面手工填上那一对再点「否决」即可。
 * 判断对这条连接的所有用户生效，每次改动都写审计。</p>
 */
export const TableRelationsPanel = memo(function TableRelationsPanel({ connections, defaultConnectionId }: {
  connections: Connection[];
  defaultConnectionId?: number;
}) {
  const usable = useMemo(() => connections.filter((item) => hasConnectionPermission(item, 'QUERY')), [connections]);
  const [connectionId, setConnectionId] = useState<number | undefined>(
    usable.some((item) => item.id === defaultConnectionId) ? defaultConnectionId : usable[0]?.id
  );
  const [data, setData] = useState<TableRelationsResponse>();
  const [loading, setLoading] = useState(false);
  const [busyKey, setBusyKey] = useState<string>();
  const [error, setError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [manual, setManual] = useState<TableRelationPair>(EMPTY_PAIR);
  const [manualProblem, setManualProblem] = useState('');

  const load = useCallback(async (refresh = false) => {
    if (!connectionId) return;
    setLoading(true);
    setError('');
    try {
      setData(await api<TableRelationsResponse>(`/metadata/${connectionId}/table-relations${refresh ? '?refresh=true' : ''}`));
    } catch (failure) {
      setData(undefined);
      setError(localizeError(failure));
    } finally {
      setLoading(false);
    }
  }, [connectionId]);

  useEffect(() => { void load(); }, [load]);

  const send = async (pair: TableRelationPair, method: 'PUT' | 'DELETE', status?: TableRelationStatus, source?: TableRelationSource) => {
    if (!connectionId) return false;
    const key = `${method}:${pairKey(pair)}`;
    setBusyKey(key);
    setError('');
    try {
      const body = { leftTable: pair.leftTable.trim(), leftColumn: pair.leftColumn.trim(), rightTable: pair.rightTable.trim(), rightColumn: pair.rightColumn.trim(), status, source };
      setData(await api<TableRelationsResponse>(`/metadata/${connectionId}/table-relations`, {
        method,
        body: JSON.stringify(body)
      }));
      // 补全那边缓存了这条连接的关系，改完立刻让它重取。
      invalidateRelationKnowledge(connectionId);
      return true;
    } catch (failure) {
      setError(localizeError(failure));
      return false;
    } finally {
      setBusyKey(undefined);
    }
  };

  const submitManual = async (status: TableRelationStatus) => {
    const problem = manualRelationProblem(manual);
    setManualProblem(problem ?? '');
    if (problem) return;
    if (await send(manual, 'PUT', status, 'MANUAL')) setManual(EMPTY_PAIR);
  };

  const mined = filterRelations(data?.mined ?? [], keyword);
  const decided = filterRelations(data?.decided ?? [], keyword);

  return (
    <Space className="full-width" orientation="vertical" size="middle">
      <Paragraph type="secondary" className="table-relations-intro">
        在 SQL 编辑器里写到 <Text code>JOIN 表 ON</Text> 时，补全会给出整条关联条件。这里管理它的来源：确认的排在最前，
        否决的不再提示（包括按命名推断出来的错误条件）。候选来自这条连接上最近成功执行过的 SQL，只统计「表.列 = 表.列」，不保存也不显示 SQL 原文。
        判断对使用这条连接的所有人生效。
      </Paragraph>
      <Space wrap>
        <Select style={{ width: 260 }} value={connectionId} placeholder="选择连接" onChange={setConnectionId}
          options={usable.map((item) => ({ value: item.id, label: `${item.name}（${item.dbType}）` }))} />
        <Input.Search allowClear style={{ width: 220 }} placeholder="按表名或列名筛选" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
        <Tooltip title="历史挖掘结果会缓存几分钟；刚执行过的新写法要重新挖一次才会出现">
          <Button icon={<ReloadOutlined />} loading={loading} disabled={!connectionId} onClick={() => void load(true)}>重新挖掘历史</Button>
        </Tooltip>
        {data && <Text type="secondary">扫描了最近 {data.scannedStatements} 条成功执行的 SQL</Text>}
      </Space>
      {error && <Alert type="error" showIcon title={error} closable onClose={() => setError('')} />}

      <section>
        <Text strong>执行历史里的候选（{mined.length}）</Text>
        <Table<MinedTableRelation>
          size="small"
          rowKey={pairKey}
          loading={loading}
          dataSource={mined}
          pagination={{ pageSize: 10, size: 'small', hideOnSinglePage: true, showSizeChanger: false }}
          locale={{ emptyText: data ? '没有待确认的候选：历史里还没出现过两张表之间带前缀的等值关联，或者都已经判断过了。' : '—' }}
          columns={[
            { title: '关联', key: 'pair', render: (_, row) => <Text code>{relationText(row)}</Text> },
            { title: '次数', dataIndex: 'hits', width: 72, align: 'right' },
            { title: '最近使用', dataIndex: 'lastUsedAt', width: 170, render: (value?: string | null) => formatTime(value) },
            {
              title: '判断', key: 'actions', width: 150,
              render: (_, row) => (
                <Space size={4}>
                  <Button size="small" icon={<CheckOutlined />} loading={busyKey === `PUT:${pairKey(row)}`} onClick={() => void send(row, 'PUT', 'CONFIRMED', 'HISTORY')}>确认</Button>
                  <Button size="small" icon={<CloseOutlined />} onClick={() => void send(row, 'PUT', 'REJECTED', 'HISTORY')}>否决</Button>
                </Space>
              )
            }
          ]}
        />
      </section>

      <section>
        <Text strong>已判断（{decided.length}）</Text>
        <Table<TableRelation>
          size="small"
          rowKey={pairKey}
          loading={loading}
          dataSource={decided}
          pagination={{ pageSize: 10, size: 'small', hideOnSinglePage: true, showSizeChanger: false }}
          locale={{ emptyText: '还没有确认或否决过的关联' }}
          columns={[
            { title: '关联', key: 'pair', render: (_, row) => <Text code>{relationText(row)}</Text> },
            { title: '判断', dataIndex: 'status', width: 84, render: (status: TableRelationStatus) => status === 'CONFIRMED' ? <Tag color="success">已确认</Tag> : <Tag>已否决</Tag> },
            { title: '来源', dataIndex: 'source', width: 90, render: (source: TableRelationSource) => SOURCE_LABELS[source] ?? source },
            { title: '判断人 / 时间', key: 'by', width: 200, render: (_, row) => <Text type="secondary">{row.decidedBy || '—'} · {formatTime(row.decidedAt)}</Text> },
            {
              title: '操作', key: 'actions', width: 170,
              render: (_, row) => (
                <Space size={4}>
                  <Button size="small" onClick={() => void send(row, 'PUT', row.status === 'CONFIRMED' ? 'REJECTED' : 'CONFIRMED', row.source)}>
                    {row.status === 'CONFIRMED' ? '改为否决' : '改为确认'}
                  </Button>
                  <Tooltip title="删除这条判断：历史里的候选会重新出现在上面">
                    <Button size="small" type="text" icon={<DeleteOutlined />} aria-label={`删除 ${relationText(row)}`} loading={busyKey === `DELETE:${pairKey(row)}`}
                      onClick={() => void send(row, 'DELETE')} />
                  </Tooltip>
                </Space>
              )
            }
          ]}
        />
      </section>

      <section className="table-relations-manual">
        <Text strong>手工添加</Text>
        <Paragraph type="secondary">只填名字本身（不带 Schema）。补全给出的某条命名推断不对时，在这里填上那一对点「否决」，之后就不会再提示。</Paragraph>
        <Space wrap align="center">
          <Input style={{ width: 160 }} placeholder="表 A" value={manual.leftTable} onChange={(event) => setManual({ ...manual, leftTable: event.target.value })} />
          <Text>.</Text>
          <Input style={{ width: 140 }} placeholder="列 A" value={manual.leftColumn} onChange={(event) => setManual({ ...manual, leftColumn: event.target.value })} />
          <Text strong>=</Text>
          <Input style={{ width: 160 }} placeholder="表 B" value={manual.rightTable} onChange={(event) => setManual({ ...manual, rightTable: event.target.value })} />
          <Text>.</Text>
          <Input style={{ width: 140 }} placeholder="列 B" value={manual.rightColumn} onChange={(event) => setManual({ ...manual, rightColumn: event.target.value })} />
          <Button type="primary" icon={<CheckOutlined />} disabled={!connectionId} onClick={() => void submitManual('CONFIRMED')}>确认关联</Button>
          <Button icon={<CloseOutlined />} disabled={!connectionId} onClick={() => void submitManual('REJECTED')}>否决</Button>
        </Space>
        {manualProblem && <Text type="danger" role="alert">{manualProblem}</Text>}
      </section>
    </Space>
  );
});
