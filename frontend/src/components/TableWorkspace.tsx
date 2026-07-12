import { useEffect, useState } from 'react';
import { Badge, Button, Drawer, Layout, Select, Space, Typography, Upload } from 'antd';
import {
  ArrowLeftOutlined,
  CloudServerOutlined,
  EyeOutlined,
  LeftOutlined,
  PlusOutlined,
  ReloadOutlined,
  RightOutlined,
  SaveOutlined,
  UploadOutlined
} from '@ant-design/icons';
import type { ActiveTable, TableData, TableRow, WorkspaceStatus } from '../types';
import { EditableTable } from './EditableTable';
import { SqlPreview } from './SqlPreview';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';

const { Header } = Layout;
const { Text } = Typography;
const TABLE_PAGE_SIZE_OPTIONS = [50, 100, 200];

export function TableWorkspace({
  activeTable,
  tableData,
  tableRows,
  previewSql,
  pendingCount,
  status,
  loading,
  readonlyConnection = false,
  editingSupported = true,
  page = 0,
  pageSize = 100,
  hasMore = false,
  onBackToSql,
  onBackupTable,
  onReload,
  onAddRow,
  onImportFile,
  onPreview,
  onCommit,
  onEdit,
  onDelete,
  onPageChange,
  onPageSizeChange
}: {
  activeTable: ActiveTable | null;
  tableData: TableData | null;
  tableRows: TableRow[];
  previewSql: string[];
  pendingCount: number;
  status: WorkspaceStatus;
  loading: boolean;
  readonlyConnection?: boolean;
  editingSupported?: boolean;
  page?: number;
  pageSize?: number;
  hasMore?: boolean;
  onBackToSql: () => void;
  onBackupTable?: () => void;
  onReload: () => void;
  onAddRow: () => void;
  onImportFile: (file: File) => void;
  onPreview: () => void;
  onCommit: () => void;
  onEdit: (rowId: string, column: string, value: unknown) => void;
  onDelete: (rowId: string) => void;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (pageSize: number) => void;
}) {
  const [previewOpen, setPreviewOpen] = useState(false);
  const tableName = activeTable ? `${activeTable.schemaName ? `${activeTable.schemaName}.` : ''}${activeTable.tableName}` : '未选择表';
  const activeTableKey = activeTable ? `${activeTable.schemaName || ''}.${activeTable.tableName}` : '';
  const editingDisabled = readonlyConnection || !editingSupported;

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
              : !editingSupported
                ? '当前数据库方言未开放表数据编辑'
              : tableData?.editable
                ? `可编辑，行定位字段：${tableData.keyColumns.join(', ')}`
                : '当前表没有主键或全非空唯一索引，只允许新增数据'}
            {tableData?.navigationMode === 'OFFSET' ? ' · 当前使用偏移分页，深页浏览受限' : ''}
          </Text>
        </div>
        <Space size={8} wrap>
          <Button
            size="small"
            icon={<CloudServerOutlined />}
            disabled={!activeTable || loading || !onBackupTable}
            onClick={onBackupTable}
          >
            备份此表
          </Button>
          <Button size="small" icon={<ReloadOutlined />} disabled={!activeTable || loading} onClick={onReload}>刷新数据</Button>
          <Button size="small" icon={<PlusOutlined />} disabled={!tableData || loading || editingDisabled} onClick={onAddRow}>新增行</Button>
          <Upload
            accept=".csv,.json,.sql"
            showUploadList={false}
            disabled={!tableData || loading || editingDisabled}
            beforeUpload={(file) => {
              onImportFile(file);
              return false;
            }}
          >
            <Button size="small" icon={<UploadOutlined />} disabled={!tableData || loading || editingDisabled}>导入</Button>
          </Upload>
          <Button
            size="small"
            icon={<EyeOutlined />}
            disabled={!pendingCount || loading || editingDisabled}
            onClick={() => {
              setPreviewOpen(true);
              onPreview();
            }}
          >
            预览语句
          </Button>
          <Button size="small" type="primary" icon={<SaveOutlined />} disabled={!pendingCount || loading || editingDisabled} loading={loading} onClick={onCommit}>提交</Button>
        </Space>
      </Header>
      <div className="table-grid-pane">
        <EditableTable data={tableData} rows={tableRows} readonly={editingDisabled} loading={loading} onEdit={onEdit} onDelete={onDelete} />
      </div>
      <div className="grid-pagination table-pagination">
        <Text type="secondary">第 {page + 1} 页 · 本页 {tableRows.length} 行</Text>
        <Space size={8} wrap={false}>
          <Text type="secondary">每页</Text>
          <Select
            size="small"
            className="table-page-size-select"
            value={pageSize}
            options={TABLE_PAGE_SIZE_OPTIONS.map((value) => ({ value, label: `${value} 行` }))}
            disabled={!tableData || loading || !onPageSizeChange}
            onChange={onPageSizeChange}
          />
          <Button
            size="small"
            icon={<LeftOutlined />}
            disabled={!tableData || loading || page <= 0 || !onPageChange}
            onClick={() => onPageChange?.(page - 1)}
          >
            上一页
          </Button>
          <Button
            size="small"
            icon={<RightOutlined />}
            iconPosition="end"
            disabled={!tableData || loading || !hasMore || !onPageChange}
            onClick={() => onPageChange?.(page + 1)}
          >
            下一页
          </Button>
        </Space>
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
