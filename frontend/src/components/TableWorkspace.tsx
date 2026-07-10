import { useEffect, useState } from 'react';
import { Badge, Button, Drawer, Layout, Space, Typography, Upload } from 'antd';
import { ArrowLeftOutlined, EyeOutlined, PlusOutlined, ReloadOutlined, SaveOutlined, UploadOutlined } from '@ant-design/icons';
import type { ActiveTable, TableData, TableRow, WorkspaceStatus } from '../types';
import { EditableTable } from './EditableTable';
import { SqlPreview } from './SqlPreview';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';

const { Header } = Layout;
const { Text } = Typography;

export function TableWorkspace({ activeTable, tableData, tableRows, previewSql, pendingCount, status, loading, readonlyConnection = false, onBackToSql, onReload, onAddRow, onImportFile, onPreview, onCommit, onEdit, onDelete }: {
  activeTable: ActiveTable | null;
  tableData: TableData | null;
  tableRows: TableRow[];
  previewSql: string[];
  pendingCount: number;
  status: WorkspaceStatus;
  loading: boolean;
  readonlyConnection?: boolean;
  onBackToSql: () => void;
  onReload: () => void;
  onAddRow: () => void;
  onImportFile: (file: File) => void;
  onPreview: () => void;
  onCommit: () => void;
  onEdit: (rowId: string, column: string, value: string) => void;
  onDelete: (rowId: string) => void;
}) {
  const [previewOpen, setPreviewOpen] = useState(false);
  const tableName = activeTable ? `${activeTable.schemaName ? `${activeTable.schemaName}.` : ''}${activeTable.tableName}` : '未选择表';

  const activeTableKey = activeTable ? `${activeTable.schemaName || ''}.${activeTable.tableName}` : '';

  useEffect(() => {
    setPreviewOpen(false);
  }, [activeTableKey]);

  useEffect(() => {
    if (previewSql.length === 0 && !loading) setPreviewOpen(false);
  }, [previewSql.length, loading]);

  return (
    <div className="workspace table-workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Space size={8}>
            <Button type="text" size="small" icon={<ArrowLeftOutlined />} aria-label="返回查询工作台" onClick={onBackToSql} />
            <Text strong>{tableName}</Text>
          </Space>
          <Text type="secondary">
            {readonlyConnection
              ? '当前连接为只读连接'
              : tableData?.editable
                ? `可编辑，行定位字段：${tableData.keyColumns.join(', ')}`
                : '当前表没有主键或唯一索引，只允许新增数据'}
          </Text>
        </div>
        <Space size={8} wrap>
          <Button size="small" icon={<ReloadOutlined />} disabled={!activeTable || loading} onClick={onReload}>重新加载</Button>
          <Button size="small" icon={<PlusOutlined />} disabled={!tableData || loading || readonlyConnection} onClick={onAddRow}>新增行</Button>
          <Upload
            accept=".csv,.json,.sql"
            showUploadList={false}
            disabled={!tableData || loading || readonlyConnection}
            beforeUpload={(file) => {
              onImportFile(file);
              return false;
            }}
          >
            <Button size="small" icon={<UploadOutlined />} disabled={!tableData || loading || readonlyConnection}>导入</Button>
          </Upload>
          <Button
            size="small"
            icon={<EyeOutlined />}
            disabled={!pendingCount || loading || readonlyConnection}
            onClick={() => {
              setPreviewOpen(true);
              onPreview();
            }}
          >
            预览语句
          </Button>
          <Button size="small" type="primary" icon={<SaveOutlined />} disabled={!pendingCount || loading || readonlyConnection} loading={loading} onClick={onCommit}>提交</Button>
        </Space>
      </Header>
      <div className="table-grid-pane">
        <EditableTable data={tableData} rows={tableRows} readonly={readonlyConnection} loading={loading} onEdit={onEdit} onDelete={onDelete} />
      </div>
      <WorkspaceStatusBar
        status={status}
        trailing={pendingCount > 0 ? <Badge status="warning" text={`待提交 ${pendingCount} 项`} /> : <Text type="secondary">无待提交变更</Text>}
      />
      <Drawer
        title="变更语句预览"
        placement="bottom"
        height={260}
        open={previewOpen}
        getContainer={false}
        rootClassName="workspace-bottom-drawer"
        onClose={() => setPreviewOpen(false)}
      >
        <SqlPreview sql={previewSql} />
      </Drawer>
    </div>
  );
}
