import { Alert, Button, Layout, Space, Typography, Upload } from 'antd';
import { PlusOutlined, ReloadOutlined, SaveOutlined, UploadOutlined } from '@ant-design/icons';
import type { ActiveTable, TableData, TableRow } from '../types';
import { EditableTable } from './EditableTable';
import { SqlPreview } from './SqlPreview';

const { Header } = Layout;
const { Text } = Typography;

export function TableWorkspace({ activeTable, tableData, tableRows, previewSql, pendingCount, statusMessage, loading, onBackToSql, onReload, onAddRow, onImportFile, onPreview, onCommit, onEdit, onDelete }: {
  activeTable: ActiveTable | null;
  tableData: TableData | null;
  tableRows: TableRow[];
  previewSql: string[];
  pendingCount: number;
  statusMessage: string;
  loading: boolean;
  onBackToSql: () => void;
  onReload: () => void;
  onAddRow: () => void;
  onImportFile: (file: File) => void;
  onPreview: () => void;
  onCommit: () => void;
  onEdit: (rowId: string, column: string, value: string) => void;
  onDelete: (rowId: string) => void;
}) {
  const tableName = activeTable ? `${activeTable.schemaName ? `${activeTable.schemaName}.` : ''}${activeTable.tableName}` : '未选择表';
  return (
    <div className="workspace table-workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>{tableName}</Text>
          <Text type="secondary">{tableData?.editable ? `可编辑，行定位字段：${tableData.keyColumns.join(', ')}` : '当前表没有主键或唯一索引，只允许新增数据'}</Text>
        </div>
        <Space size={8} wrap>
          <Button size="small" onClick={onBackToSql}>查询工作台</Button>
          <Button size="small" icon={<ReloadOutlined />} disabled={!activeTable || loading} onClick={onReload}>重新加载</Button>
          <Button size="small" icon={<PlusOutlined />} disabled={!tableData || loading} onClick={onAddRow}>新增行</Button>
          <Upload
            accept=".csv,.json,.sql"
            showUploadList={false}
            beforeUpload={(file) => {
              onImportFile(file);
              return false;
            }}
          >
            <Button size="small" icon={<UploadOutlined />} disabled={!tableData || loading}>导入</Button>
          </Upload>
          <Button size="small" disabled={!pendingCount || loading} onClick={onPreview}>预览语句</Button>
          <Button size="small" type="primary" icon={<SaveOutlined />} disabled={!pendingCount || loading} loading={loading} onClick={onCommit}>提交</Button>
        </Space>
      </Header>
      <Alert className="status-alert" type={loading ? 'info' : 'success'} message={statusMessage} showIcon />
      <EditableTable data={tableData} rows={tableRows} onEdit={onEdit} onDelete={onDelete} />
      <SqlPreview sql={previewSql} />
    </div>
  );
}
