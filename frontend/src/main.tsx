import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import Editor from '@monaco-editor/react';
import { Database, Play, Plus, RefreshCw, Save, Table2, Trash2 } from 'lucide-react';
import './styles.css';

const API = 'http://localhost:8080/api';

const FIELD_LABELS = {
  name: '连接名称',
  dbType: '数据库类型',
  jdbcUrl: '数据库地址',
  username: '用户名',
  password: '密码',
  environment: '环境'
} as const;

const DB_TYPE_OPTIONS = [
  { value: 'h2', label: 'H2', url: 'jdbc:h2:mem:testdb' },
  { value: 'mysql', label: 'MySQL', url: 'jdbc:mysql://localhost:3306/demo' },
  { value: 'postgresql', label: 'PostgreSQL', url: 'jdbc:postgresql://localhost:5432/demo' },
  { value: 'oracle', label: 'Oracle', url: 'jdbc:oracle:thin:@//localhost:1521/ORCLPDB1' },
  { value: 'sqlserver', label: 'SQL Server', url: 'jdbc:sqlserver://localhost:1433;databaseName=demo' },
  { value: 'sqlite', label: 'SQLite', url: 'jdbc:sqlite:/tmp/demo.db' },
  { value: 'mariadb', label: 'MariaDB', url: 'jdbc:mariadb://localhost:3306/demo' },
  { value: 'clickhouse', label: 'ClickHouse', url: 'jdbc:clickhouse://localhost:8123/default' }
];

type Connection = {
  id: number;
  name: string;
  dbType: string;
  jdbcUrl: string;
  username?: string;
  environment: string;
  readonly: boolean;
};

type DbObject = {
  schemaName?: string;
  name: string;
  type: string;
  columns: { name: string; type: string; size: number; nullable: boolean }[];
  indexes: { name: string; columnName: string; unique: boolean }[];
};

type Metadata = { schemas: string[]; objects: DbObject[] };
type SqlResult = { columns: string[]; rows: Record<string, unknown>[]; affectedRows: number; elapsedMs: number; resultSet: boolean };
type BackupTask = { id: number; name: string; connectionId: number; scope: string; cron?: string; lastStatus?: string; lastMessage?: string };
type ActiveTable = { schemaName?: string; tableName: string };
type TableRow = { id: string; values: Record<string, unknown>; original?: Record<string, unknown>; deleted?: boolean; inserted?: boolean };
type TableData = { columns: string[]; rows: Record<string, unknown>[]; keyColumns: string[]; editable: boolean };
type RowChange = { type: 'INSERT' | 'UPDATE' | 'DELETE'; key?: Record<string, unknown>; values?: Record<string, unknown> };

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', 'X-User': 'admin', ...(init?.headers || {}) }
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }));
    throw new Error(err.message || res.statusText);
  }
  return res.json();
}

function App() {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [selected, setSelected] = useState<Connection | null>(null);
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [sql, setSql] = useState('select 1 as val');
  const [result, setResult] = useState<SqlResult | null>(null);
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [backups, setBackups] = useState<BackupTask[]>([]);
  const [form, setForm] = useState({ name: '本地 H2', dbType: 'h2', jdbcUrl: 'jdbc:h2:mem:testdb', username: 'sa', password: '', environment: 'dev' });
  const [mode, setMode] = useState<'sql' | 'table'>('sql');
  const [activeTable, setActiveTable] = useState<ActiveTable | null>(null);
  const [tableData, setTableData] = useState<TableData | null>(null);
  const [tableRows, setTableRows] = useState<TableRow[]>([]);
  const [previewSql, setPreviewSql] = useState<string[]>([]);

  const objects = useMemo(() => metadata?.objects || [], [metadata]);
  const pendingChanges = useMemo(() => buildChanges(tableRows, tableData?.keyColumns || []), [tableRows, tableData]);

  useEffect(() => {
    refreshConnections();
    refreshBackups();
  }, []);

  async function refreshConnections() {
    const rows = await api<Connection[]>('/connections');
    setConnections(rows);
    setSelected((current: Connection | null) => current || rows[0] || null);
  }

  async function refreshBackups() {
    setBackups(await api<BackupTask[]>('/backups'));
  }

  async function createConnection() {
    setLoading(true);
    try {
      const created = await api<Connection>('/connections', { method: 'POST', body: JSON.stringify(form) });
      setSelected(created);
      setMessage(`已创建连接：${created.name}`);
      await refreshConnections();
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  async function loadMetadata(conn = selected) {
    if (!conn) return;
    setLoading(true);
    try {
      const data = await api<Metadata>(`/metadata/${conn.id}`);
      setMetadata(data);
      setMessage(`已加载 ${data.objects.length} 个数据库对象`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  async function execute(path = '/sql/execute') {
    if (!selected) {
      setMessage('请先选择一个数据库连接');
      return;
    }
    setMode('sql');
    setLoading(true);
    try {
      const data = await api<SqlResult>(path, { method: 'POST', body: JSON.stringify({ connectionId: selected.id, sql, maxRows: 500 }) });
      setResult(data);
      setMessage(data.resultSet ? `返回 ${data.rows.length} 行，用时 ${data.elapsedMs}ms` : `影响 ${data.affectedRows} 行`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  async function formatSql() {
    const data = await api<{ sql: string }>('/sql/format', { method: 'POST', body: JSON.stringify({ sql }) });
    setSql(data.sql);
  }

  async function openTable(object: DbObject) {
    if (!selected || object.type.toUpperCase().includes('VIEW')) {
      return;
    }
    const next = { schemaName: object.schemaName, tableName: object.name };
    setActiveTable(next);
    setMode('table');
    await loadTable(next);
  }

  async function loadTable(table = activeTable) {
    if (!selected || !table) return;
    setLoading(true);
    try {
      const params = new URLSearchParams({
        connectionId: String(selected.id),
        tableName: table.tableName,
        page: '0',
        pageSize: '100'
      });
      if (table.schemaName) params.set('schemaName', table.schemaName);
      const data = await api<TableData>(`/data/table?${params.toString()}`);
      setTableData(data);
      setTableRows(data.rows.map((row, index) => ({ id: `row-${index}`, values: { ...row }, original: { ...row } })));
      setPreviewSql([]);
      setMessage(`已从 ${table.tableName} 加载 ${data.rows.length} 行数据`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  function editCell(rowId: string, column: string, value: string) {
    setTableRows((rows) => rows.map((row) => row.id === rowId ? { ...row, values: { ...row.values, [column]: value } } : row));
    setPreviewSql([]);
  }

  function changeDbType(dbType: string) {
    const preset = DB_TYPE_OPTIONS.find((option) => option.value === dbType);
    setForm((current) => ({
      ...current,
      dbType,
      jdbcUrl: preset ? preset.url : current.jdbcUrl
    }));
  }

  function addRow() {
    if (!tableData) return;
    const empty = Object.fromEntries(tableData.columns.map((column) => [column, '']));
    setTableRows((rows) => [{ id: `new-${Date.now()}`, values: empty, inserted: true }, ...rows]);
    setPreviewSql([]);
  }

  function deleteRow(rowId: string) {
    setTableRows((rows) => rows.flatMap((row) => {
      if (row.id !== rowId) return [row];
      return row.inserted ? [] : [{ ...row, deleted: true }];
    }));
    setPreviewSql([]);
  }

  async function previewChanges() {
    if (!selected || !activeTable) return;
    if (!pendingChanges.length) {
      setMessage('没有待提交的变更');
      return;
    }
    setLoading(true);
    try {
      const data = await api<{ sql: string[] }>('/data/preview', { method: 'POST', body: JSON.stringify(dataChangePayload()) });
      setPreviewSql(data.sql);
      setMessage(`已生成 ${data.sql.length} 条变更语句`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  async function commitChanges() {
    if (!selected || !activeTable) return;
    if (!pendingChanges.length) {
      setMessage('没有待提交的变更');
      return;
    }
    setLoading(true);
    try {
      const data = await api<{ sql: string[]; affectedRows: number }>('/data/commit', { method: 'POST', body: JSON.stringify(dataChangePayload()) });
      setPreviewSql(data.sql);
      setMessage(`已提交，影响 ${data.affectedRows} 行`);
      await loadTable(activeTable);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  function dataChangePayload() {
    return {
      connectionId: selected?.id,
      schemaName: activeTable?.schemaName,
      tableName: activeTable?.tableName,
      changes: pendingChanges
    };
  }

  async function createBackup() {
    if (!selected) return;
    const task = await api<BackupTask>('/backups', {
      method: 'POST',
      body: JSON.stringify({ name: `${selected.name} 全量备份`, connectionId: selected.id, scope: 'DATABASE', cron: '0 0 2 * * *', enabled: true })
    });
    setMessage(`已创建备份任务：${task.name}`);
    await refreshBackups();
  }

  async function runBackup(id: number) {
    const task = await api<BackupTask>(`/backups/${id}/run`, { method: 'POST' });
    setMessage(localizeMessage(task.lastMessage || '备份任务已执行'));
    await refreshBackups();
  }

  return (
    <main className="app">
      <aside className="sidebar">
        <div className="brand"><Database size={18} /> 数据库管理工具</div>
        <button className="primary" onClick={refreshConnections}><RefreshCw size={15} /> 刷新连接</button>
        <div className="list">
          {connections.map((c) => (
            <button key={c.id} className={selected?.id === c.id ? 'selected' : ''} onClick={() => { setSelected(c); setMetadata(null); setMode('sql'); }}>
              <strong>{c.name}</strong>
              <span>{c.dbType} · {c.environment}</span>
            </button>
          ))}
        </div>
        <section className="compact">
          <h3>数据库对象</h3>
          <button onClick={() => loadMetadata()} disabled={!selected || loading}><Table2 size={14} /> 加载对象</button>
          <div className="objects">
            {objects.map((o) => (
              <details key={`${o.schemaName}.${o.name}`}>
                <summary onClick={() => openTable(o)}>{o.schemaName ? `${o.schemaName}.` : ''}{o.name} <em>{objectTypeLabel(o.type)}</em></summary>
                {o.columns.map((col) => <div className="column" key={col.name}>{col.name} <span>{col.type}</span></div>)}
              </details>
            ))}
          </div>
        </section>
      </aside>

      {mode === 'sql' ? (
        <section className="workspace">
          <header className="toolbar">
            <div>
              <strong>{selected ? selected.name : '未选择连接'}</strong>
              <span>{selected?.jdbcUrl}</span>
            </div>
            <button onClick={formatSql}>格式化</button>
            <button onClick={() => execute('/sql/explain')} disabled={!selected || loading}>执行计划</button>
            <button className="run" onClick={() => execute()} disabled={!selected || loading}><Play size={15} /> 执行</button>
          </header>
          <div className="editor">
            <Editor height="100%" language="sql" value={sql} onChange={(value) => setSql(value || '')} theme="vs-dark" options={{ minimap: { enabled: false }, fontSize: 14 }} />
          </div>
          <div className="status">{loading ? '处理中...' : message}</div>
          <ResultGrid result={result} />
        </section>
      ) : (
        <section className="workspace table-workspace">
          <header className="toolbar">
            <div>
              <strong>{activeTable ? `${activeTable.schemaName ? `${activeTable.schemaName}.` : ''}${activeTable.tableName}` : '未选择表'}</strong>
              <span>{tableData?.editable ? `可编辑，行定位字段：${tableData.keyColumns.join(', ')}` : '当前表没有主键或唯一索引，只允许新增数据'}</span>
            </div>
            <button onClick={() => setMode('sql')}>查询工作台</button>
            <button onClick={() => loadTable()} disabled={!activeTable || loading}><RefreshCw size={14} /> 重新加载</button>
            <button onClick={addRow} disabled={!tableData || loading}><Plus size={14} /> 新增行</button>
            <button onClick={previewChanges} disabled={!pendingChanges.length || loading}>预览语句</button>
            <button className="run" onClick={commitChanges} disabled={!pendingChanges.length || loading}><Save size={14} /> 提交</button>
          </header>
          <div className="status">{loading ? '处理中...' : `${message} · 待提交变更：${pendingChanges.length}`}</div>
          <EditableTable data={tableData} rows={tableRows} onEdit={editCell} onDelete={deleteRow} />
          <SqlPreview sql={previewSql} />
        </section>
      )}

      <aside className="inspector">
        <section>
          <h2>新建连接</h2>
          <label>{FIELD_LABELS.name}<input type="text" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label>{FIELD_LABELS.dbType}
            <select value={form.dbType} onChange={(e) => changeDbType(e.target.value)}>
              {DB_TYPE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>
          <label>{FIELD_LABELS.jdbcUrl}<input type="text" value={form.jdbcUrl} onChange={(e) => setForm({ ...form, jdbcUrl: e.target.value })} /></label>
          <label>{FIELD_LABELS.username}<input type="text" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} /></label>
          <label>{FIELD_LABELS.password}<input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></label>
          <label>{FIELD_LABELS.environment}<input type="text" value={form.environment} onChange={(e) => setForm({ ...form, environment: e.target.value })} /></label>
          {form.dbType === 'oracle' && <p className="hint">Oracle Service Name 示例：jdbc:oracle:thin:@//localhost:1521/ORCLPDB1；SID 示例：jdbc:oracle:thin:@localhost:1521:ORCL</p>}
          <button className="primary" onClick={createConnection} disabled={loading}><Save size={15} /> 保存连接</button>
        </section>
        <section>
          <h2>备份任务</h2>
          <button onClick={createBackup} disabled={!selected}>创建全量备份任务</button>
          <div className="backup-list">
            {backups.map((b) => (
              <div className="backup" key={b.id}>
                <strong>{b.name}</strong>
                <span>{backupScopeLabel(b.scope)} · {b.cron || '手动执行'}</span>
                <span>{backupStatusLabel(b.lastStatus)}</span>
                <button onClick={() => runBackup(b.id)}>执行</button>
              </div>
            ))}
          </div>
        </section>
      </aside>
    </main>
  );
}

function buildChanges(rows: TableRow[], keyColumns: string[]): RowChange[] {
  const changes: RowChange[] = [];
  for (const row of rows) {
    if (row.inserted) {
      changes.push({ type: 'INSERT', values: removeEmptyValues(row.values) });
      continue;
    }
    if (!row.original) continue;
    if (row.deleted) {
      changes.push({ type: 'DELETE', key: key(row.original, keyColumns) });
      continue;
    }
    const changedValues = diff(row.original, row.values);
    if (Object.keys(changedValues).length > 0) {
      changes.push({ type: 'UPDATE', key: key(row.original, keyColumns), values: changedValues });
    }
  }
  return changes;
}

function key(row: Record<string, unknown>, keyColumns: string[]) {
  return Object.fromEntries(keyColumns.map((column) => [column, row[column]]));
}

function diff(original: Record<string, unknown>, values: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(values).filter(([column, value]) => String(original[column] ?? '') !== String(value ?? '')));
}

function removeEmptyValues(values: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== ''));
}

function ResultGrid({ result }: { result: SqlResult | null }) {
  if (!result) return <div className="empty">执行查询后查看结果。</div>;
  if (!result.resultSet) return <div className="empty">影响 {result.affectedRows} 行。</div>;
  return (
    <div className="grid-wrap">
      <table>
        <thead><tr>{result.columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
        <tbody>
          {result.rows.map((row, index) => (
            <tr key={index}>{result.columns.map((c) => <td key={c}>{String(row[c] ?? '')}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EditableTable({ data, rows, onEdit, onDelete }: {
  data: TableData | null;
  rows: TableRow[];
  onEdit: (rowId: string, column: string, value: string) => void;
  onDelete: (rowId: string) => void;
}) {
  if (!data) return <div className="empty">点击左侧对象树中的表来浏览数据。</div>;
  return (
    <div className="grid-wrap editable-grid">
      <table>
        <thead>
          <tr>
            <th>操作</th>
            {data.columns.map((column) => <th key={column}>{column}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className={row.deleted ? 'deleted-row' : row.inserted ? 'inserted-row' : ''}>
              <td><button className="icon-button" onClick={() => onDelete(row.id)} disabled={row.deleted || (!data.editable && !row.inserted)}><Trash2 size={14} /></button></td>
              {data.columns.map((column) => (
                <td key={column}>
                  <input
                    className="cell-input"
                    disabled={row.deleted || (!data.editable && !row.inserted)}
                    value={String(row.values[column] ?? '')}
                    onChange={(e) => onEdit(row.id, column, e.target.value)}
                  />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SqlPreview({ sql }: { sql: string[] }) {
  return (
    <div className="sql-preview">
      <strong>变更语句预览</strong>
      {sql.length === 0 ? <span>尚未生成预览。</span> : <pre>{sql.join('\n')}</pre>}
    </div>
  );
}

function backupScopeLabel(scope: string) {
  if (scope === 'DATABASE') return '全库';
  if (scope === 'TABLE') return '单表';
  return scope;
}

function backupStatusLabel(status?: string) {
  if (!status) return '尚未执行';
  if (status === 'SUCCESS') return '执行成功';
  if (status === 'FAILED') return '执行失败';
  return status;
}

function objectTypeLabel(type: string) {
  const normalized = type.toUpperCase();
  if (normalized.includes('TABLE')) return '表';
  if (normalized.includes('VIEW')) return '视图';
  return type;
}

function localizeMessage(message: string) {
  if (!message) return '';
  if (message.includes('Backup framework executed')) return '备份任务已执行。当前数据库类型暂未实现物理备份适配器。';
  if (message.includes('Physical backup adapter is not implemented')) return '当前数据库类型暂未实现物理备份适配器。';
  if (message.includes('MySQL backup task prepared')) return 'MySQL 备份任务已准备完成，请在服务端配置物理备份命令。';
  if (message.includes('Connection not found')) return '未找到数据库连接。';
  if (message.includes('No pending changes')) return '没有待提交的变更。';
  return message;
}

createRoot(document.getElementById('root')!).render(<App />);
