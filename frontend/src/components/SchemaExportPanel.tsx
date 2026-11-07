import { memo, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Collapse, Form, Input, List, Segmented, Space, Table, Tag, Typography } from 'antd';
import { DownloadOutlined, ExportOutlined, ReloadOutlined } from '@ant-design/icons';
import { PanelEmpty } from './PanelState';
import { api, downloadBlob } from '../api';
import { ConnectionField, SchemaField } from './ConnectionScopeFields';
import { localizeError, timestamp } from '../utils';
import {
  buildSchemaExportRequest,
  candidateKey,
  emptyKindsNotice,
  kindCountLabel,
  kindCounts,
  mergeCandidates,
  schemaExportFileName,
  schemaExportKindLabel,
  schemaExportSummary,
  selectionProblem,
  supportsDefinerStripping,
  toggleCandidates,
  visibleCandidates
} from '../schemaExport';
import type { Connection, SchemaExportCandidate, SchemaExportCandidates, SchemaExportResponse } from '../types';

const { Text } = Typography;
/** 脚本预览只显示开头这一截：几百个对象的定义动辄几 MB，整段塞进 DOM 会卡住抽屉。完整内容在下载里。 */
const PREVIEW_CHARS = 60_000;

/**
 * 对象定义批量导出。
 *
 * <p>选表、视图、序列、函数、存储过程、触发器，服务端按依赖排好顺序 —— 外键指向的表在前，
 * 视图引用的视图在前 —— 合成一份能在空库里从头跑到尾的脚本。排不开的循环依赖与读失败的对象
 * 都放在结果最前面说，而不是埋在一句「导出成功」后面。</p>
 */
export const SchemaExportPanel = memo(function SchemaExportPanel({ connections, defaultConnectionId, defaultSchema, onOpenInSqlTab }: {
  connections: Connection[];
  defaultConnectionId?: number;
  defaultSchema?: string;
  onOpenInSqlTab: (sql: string, title: string, target?: { connectionId: number; schemaName?: string }) => void;
}) {
  const [connectionId, setConnectionId] = useState<number | undefined>(defaultConnectionId);
  const [schemaName, setSchemaName] = useState(defaultSchema || '');
  const [candidates, setCandidates] = useState<SchemaExportCandidates>();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [kind, setKind] = useState<string>('ALL');
  const [keyword, setKeyword] = useState('');
  const [stripDefiner, setStripDefiner] = useState(true);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [result, setResult] = useState<SchemaExportResponse>();
  const [error, setError] = useState('');
  const [searching, setSearching] = useState(false);
  const [searchNotice, setSearchNotice] = useState('');

  const connection = connections.find((item) => item.id === connectionId);
  const objects = candidates?.objects ?? [];
  const counts = useMemo(() => kindCounts(objects, selected, candidates?.kinds, candidates?.truncatedKinds),
    [objects, selected, candidates?.kinds, candidates?.truncatedKinds]);
  const emptyNotice = emptyKindsNotice(counts);
  const visible = useMemo(() => visibleCandidates(objects, kind, keyword), [objects, kind, keyword]);
  const allVisibleSelected = visible.length > 0 && visible.every((item) => selected.has(candidateKey(item)));
  const problem = selectionProblem(selected.size);

  const load = async () => {
    if (!connectionId) return;
    setLoading(true);
    setError('');
    setResult(undefined);
    try {
      const query = new URLSearchParams();
      if (schemaName.trim()) query.set('schemaName', schemaName.trim());
      const loaded = await api<SchemaExportCandidates>(`/metadata/${connectionId}/schema-export/candidates?${query.toString()}`);
      setCandidates(loaded);
      setSchemaName(loaded.schemaName || schemaName);
      setSelected(new Set());
      setKind('ALL');
      setSearchNotice('');
    } catch (failure) {
      setCandidates(undefined);
      setError(localizeError(failure));
    } finally {
      setLoading(false);
    }
  };

  // 换连接后旧清单就不再成立，清掉，免得拿 A 库的对象去 B 库导出。
  useEffect(() => {
    setCandidates(undefined);
    setSelected(new Set());
    setResult(undefined);
  }, [connectionId]);

  /**
   * 清单触到了每类上限时，按名称筛选只在已列出的那一批里找是找不全的：回车就到数据库里按名称搜，
   * 结果并进清单（已勾选的不丢）。没触顶时本地筛选已经是全集，不必多打一次接口。
   */
  const searchDatabase = async (value: string) => {
    const term = value.trim();
    if (!connectionId || !candidates?.truncated || !term) return;
    setSearching(true);
    setError('');
    try {
      const query = new URLSearchParams({ keyword: term });
      if (candidates.schemaName) query.set('schemaName', candidates.schemaName);
      const found = await api<SchemaExportCandidates>(`/metadata/${connectionId}/schema-export/candidates?${query.toString()}`);
      const merged = mergeCandidates(candidates.objects, found.objects);
      setCandidates({ ...candidates, objects: merged.objects });
      const more = found.truncated ? '；匹配的也超过了每类上限，请输入更完整的名称' : '';
      setSearchNotice(`在数据库中搜索「${term}」：匹配 ${found.objects.length} 个，新加入列表 ${merged.added} 个${more}`);
    } catch (failure) {
      setError(localizeError(failure));
    } finally {
      setSearching(false);
    }
  };

  const runExport = async () => {
    if (!connectionId || !candidates || problem) return;
    setExporting(true);
    setError('');
    try {
      const response = await api<SchemaExportResponse>(`/metadata/${connectionId}/schema-export`, {
        method: 'POST',
        body: JSON.stringify(buildSchemaExportRequest(objects, selected, candidates.schemaName || schemaName, stripDefiner))
      });
      setResult(response);
    } catch (failure) {
      setError(localizeError(failure));
    } finally {
      setExporting(false);
    }
  };

  const download = () => {
    if (!result) return;
    downloadBlob(new Blob([result.script], { type: 'text/plain;charset=utf-8' }),
      schemaExportFileName(connection?.name ?? '', result.schemaName, timestamp()));
  };

  const summary = result ? schemaExportSummary(result) : undefined;
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Form layout="vertical" size="small" className="schema-diff-endpoints">
        <Space size={12} wrap align="end">
          <ConnectionField connections={connections} value={connectionId} onChange={setConnectionId} />
          <SchemaField connectionId={connectionId} value={schemaName} onChange={setSchemaName} />
          <Form.Item>
            <Button icon={<ReloadOutlined />} loading={loading} disabled={!connectionId} onClick={() => void load()}>
              {candidates ? '重新加载对象' : '加载对象'}
            </Button>
          </Form.Item>
        </Space>
      </Form>

      {error && <Alert type="error" showIcon message={error} />}

      {!candidates ? (
        <PanelEmpty title="选择连接与 Schema 后加载对象" description="可以批量导出表、视图、物化视图、序列、函数、存储过程与触发器的定义（以当前数据库支持的为准），脚本按依赖关系排好顺序。" />
      ) : (
        <>
          {candidates.warnings.map((warning) => <Alert key={warning} type="warning" showIcon message={warning} />)}
          {emptyNotice && <Text type="secondary">{emptyNotice}</Text>}
          {searchNotice && <Alert type="info" showIcon closable message={searchNotice} onClose={() => setSearchNotice('')} />}
          <Space size={8} wrap>
            <Segmented
              size="small"
              value={kind}
              onChange={(value) => setKind(String(value))}
              options={[
                { value: 'ALL', label: `全部 ${objects.length}` },
                ...counts.map((item) => ({ value: item.kind, label: kindCountLabel(item), disabled: item.total === 0 }))
              ]}
            />
            <Input.Search size="small" allowClear style={{ width: candidates.truncated ? 260 : 200 }} loading={searching}
              placeholder={candidates.truncated ? '按名称筛选，回车到数据库中搜索' : '按名称筛选'}
              value={keyword} onChange={(event) => setKeyword(event.target.value)}
              onSearch={(value, _event, info) => { if (info?.source !== 'clear') void searchDatabase(value); }} />
            <Button size="small" disabled={visible.length === 0} onClick={() => setSelected(toggleCandidates(selected, visible, !allVisibleSelected))}>
              {allVisibleSelected ? '取消选择当前列表' : `选择当前列表（${visible.length}）`}
            </Button>
            {selected.size > 0 && <Button size="small" type="link" onClick={() => setSelected(new Set())}>清空选择</Button>}
          </Space>
          <Table<SchemaExportCandidate>
            size="small"
            rowKey={candidateKey}
            dataSource={visible}
            pagination={{ pageSize: 50, size: 'small', showSizeChanger: false, hideOnSinglePage: true }}
            scroll={{ y: 320 }}
            rowSelection={{
              selectedRowKeys: visible.filter((item) => selected.has(candidateKey(item))).map(candidateKey),
              // 只替换当前列表这一段的选择，其它类型/其它筛选条件下已选的保持不动。
              onChange: (keys) => setSelected(toggleCandidates(toggleCandidates(selected, visible, false),
                visible.filter((item) => keys.includes(candidateKey(item))), true)),
              preserveSelectedRowKeys: true
            }}
            columns={[
              { title: '名称', dataIndex: 'displayName', ellipsis: true },
              { title: '类型', dataIndex: 'kind', width: 110, render: (value: string) => <Tag>{schemaExportKindLabel(value)}</Tag> }
            ]}
          />
          <Space size={12} wrap align="center">
            <Button type="primary" icon={<ExportOutlined />} loading={exporting} disabled={Boolean(problem)} onClick={() => void runExport()}>
              按依赖顺序生成脚本{selected.size > 0 ? `（${selected.size}）` : ''}
            </Button>
            {supportsDefinerStripping(connection?.dbType) && (
              <Checkbox checked={stripDefiner} onChange={(event) => setStripDefiner(event.target.checked)}>
                去掉 DEFINER（跨环境迁移时建议勾选）
              </Checkbox>
            )}
            {problem && selected.size > 0 && <Text type="warning">{problem}</Text>}
          </Space>
        </>
      )}

      {result && summary && (
        <Space direction="vertical" size="small" style={{ width: '100%' }}>
          <Alert
            type={summary.type}
            showIcon
            message={summary.text}
            description={result.warnings.length > 0 ? (
              <List size="small" dataSource={result.warnings} renderItem={(warning) => <List.Item>{warning}</List.Item>} />
            ) : '被依赖的对象排在前面，脚本可以在空库里从头执行到尾。'}
          />
          <Space size={8} wrap>
            <Button icon={<DownloadOutlined />} disabled={result.items.length === 0} onClick={download}>下载 .sql</Button>
            <Button
              disabled={result.items.length === 0 || !connectionId}
              onClick={() => connectionId && onOpenInSqlTab(result.script, `对象导出 ${result.schemaName}`, { connectionId, schemaName: result.schemaName })}
            >
              在 SQL 工作台打开
            </Button>
          </Space>
          <Collapse
            size="small"
            items={[
              {
                key: 'order',
                label: `执行顺序（${result.items.length}）`,
                children: (
                  <List
                    size="small"
                    dataSource={result.items}
                    renderItem={(item) => (
                      <List.Item>
                        <Space size={8} wrap>
                          <Text type="secondary">{item.order}.</Text>
                          <Tag>{schemaExportKindLabel(item.kind)}</Tag>
                          <Text>{item.name}</Text>
                          {item.dependsOn.length > 0 && <Text type="secondary">依赖 {item.dependsOn.join('、')}</Text>}
                        </Space>
                      </List.Item>
                    )}
                  />
                )
              },
              {
                key: 'script',
                label: '脚本预览',
                children: (
                  <>
                    {result.script.length > PREVIEW_CHARS && <Text type="secondary">只显示前 {PREVIEW_CHARS.toLocaleString()} 个字符，完整内容请下载。</Text>}
                    <pre className="schema-export-preview">{result.script.slice(0, PREVIEW_CHARS)}</pre>
                  </>
                )
              }
            ]}
          />
        </Space>
      )}
    </Space>
  );
});
