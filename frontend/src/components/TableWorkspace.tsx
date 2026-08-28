import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { DRAWER_WIDTH } from '../constants';
import { Badge, Button, Drawer, Dropdown, Layout, Popconfirm, Select, Space, Tooltip, Typography, Upload } from 'antd';
import type { MenuProps } from 'antd';
import {
  ArrowLeftOutlined,
  CalculatorOutlined,
  CloudServerOutlined,
  DoubleLeftOutlined,
  EyeOutlined,
  LeftOutlined,
  MoreOutlined,
  PlusOutlined,
  ReloadOutlined,
  RightOutlined,
  SaveOutlined,
  UndoOutlined,
  UploadOutlined
} from '@ant-design/icons';
import type { ActiveTable, RowChange, TableData, TableRow, WorkspaceStatus } from '../types';
import { EditableTable } from './EditableTable';
import { SqlPreview } from './SqlPreview';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';
import { summarizeRowChanges } from '../utils';
import { SHORTCUT_HINTS } from '../keyboardShortcuts';
import { canCountTableRows, IDLE_TABLE_ROW_COUNT, tablePageSummary, type TableRowCountState } from '../tableRowCount';
import type { RelationTarget } from '../relationNavigation';

const { Header } = Layout;
const { Text } = Typography;
const TABLE_PAGE_SIZE_OPTIONS = [50, 100, 200];

export const TableWorkspace = memo(function TableWorkspace({
  activeTable,
  tableData,
  tableRows,
  previewSql,
  pendingChanges,
  status,
  loading,
  readonlyConnection = false,
  editingSupported = true,
  page = 0,
  pageSize = 100,
  hasMore = false,
  rowCount = IDLE_TABLE_ROW_COUNT,
  onCountRows,
  onBackToSql,
  onBackupTable,
  onReload,
  onAddRow,
  onImportFile,
  onPreview,
  onDiscardChanges,
  onCommit,
  onEdit,
  onDelete,
  foreignKeys,
  onFollowRelation,
  onPageChange,
  onPageSizeChange
}: {
  activeTable: ActiveTable | null;
  tableData: TableData | null;
  tableRows: TableRow[];
  previewSql: string[];
  pendingChanges: RowChange[];
  status: WorkspaceStatus;
  loading: boolean;
  readonlyConnection?: boolean;
  editingSupported?: boolean;
  page?: number;
  pageSize?: number;
  hasMore?: boolean;
  rowCount?: TableRowCountState;
  onCountRows?: () => void;
  onBackToSql: () => void;
  onBackupTable?: () => void;
  onReload: () => void;
  onAddRow: () => void;
  onImportFile: (file: File) => void;
  onPreview: () => void;
  onDiscardChanges: () => void;
  onCommit: () => void;
  onEdit: (rowId: string, column: string, value: unknown) => void;
  onDelete: (rowId: string) => void;
  foreignKeys?: Map<string, RelationTarget>;
  onFollowRelation?: (target: RelationTarget, value: unknown) => void;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (pageSize: number) => void;
}) {
  const [previewOpen, setPreviewOpen] = useState(false);
  const importInputRef = useRef<HTMLInputElement>(null);
  const tableName = activeTable ? `${activeTable.schemaName ? `${activeTable.schemaName}.` : ''}${activeTable.tableName}` : '未选择表';
  const activeTableKey = activeTable ? `${activeTable.schemaName || ''}.${activeTable.tableName}` : '';
  const editingDisabled = readonlyConnection || !editingSupported;
  const pendingCount = pendingChanges.length;
  // App already ran buildChanges over every row to produce this list; recomputing
  // it here meant diffing the whole table twice on each keystroke.
  const changeSummary = useMemo(() => summarizeRowChanges(pendingChanges), [pendingChanges]);
  const secondaryMenu: MenuProps = {
    items: [
      { key: 'backup', icon: <CloudServerOutlined />, label: '备份此表', disabled: !activeTable || loading || !onBackupTable },
      { key: 'reload', icon: <ReloadOutlined />, label: '刷新数据', disabled: !activeTable || loading },
      { key: 'add', icon: <PlusOutlined />, label: '新增行', disabled: !tableData || loading || editingDisabled },
      { key: 'import', icon: <UploadOutlined />, label: '导入数据', disabled: !tableData || loading || editingDisabled }
    ],
    onClick: ({ key }) => {
      if (key === 'backup') onBackupTable?.();
      if (key === 'reload') onReload();
      if (key === 'add') onAddRow();
      if (key === 'import') importInputRef.current?.click();
    }
  };

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
        <div className="table-toolbar-actions">
          <Space size={8} className="table-secondary-actions">
            <Button size="small" icon={<CloudServerOutlined />} disabled={!activeTable || loading || !onBackupTable} onClick={onBackupTable}>备份此表</Button>
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
              <Tooltip title="CSV / JSON / SQL。小文件直接进待提交变更，可逐行核对；超过 10 MB 的 CSV 自动转成后台导入任务，带进度且可取消。">
                <Button size="small" icon={<UploadOutlined />} disabled={!tableData || loading || editingDisabled}>导入</Button>
              </Tooltip>
            </Upload>
          </Space>
          <Dropdown menu={secondaryMenu} trigger={['click']}>
            <Button className="table-more-actions" size="small" icon={<MoreOutlined />} aria-label="更多表格操作">更多</Button>
          </Dropdown>
          <Space size={8} className="table-primary-actions">
            <Popconfirm
              title={`撤销全部 ${pendingCount} 项变更？`}
              description="所有尚未提交的新增、编辑和删除都会恢复。"
              okText="撤销全部"
              cancelText="保留变更"
              onConfirm={onDiscardChanges}
            >
              <Button size="small" icon={<UndoOutlined />} disabled={!pendingCount || loading}>撤销全部</Button>
            </Popconfirm>
            <Button
              size="small"
              icon={<EyeOutlined />}
              disabled={!pendingCount || loading || editingDisabled}
              onClick={() => {
                setPreviewOpen(true);
                onPreview();
              }}
            >
              预览 {pendingCount || ''}
            </Button>
            <Tooltip title={`提交待处理的表数据变更（${SHORTCUT_HINTS.commitTableChanges}）`}>
              <Button size="small" type="primary" icon={<SaveOutlined />} disabled={!pendingCount || loading || editingDisabled} loading={loading} onClick={onCommit}>提交 {pendingCount || ''}</Button>
            </Tooltip>
          </Space>
          <input
            ref={importInputRef}
            className="visually-hidden"
            type="file"
            accept=".csv,.json,.sql"
            tabIndex={-1}
            onChange={(event) => {
              const file = event.currentTarget.files?.[0];
              if (file) onImportFile(file);
              event.currentTarget.value = '';
            }}
          />
        </div>
      </Header>
      <div className="table-grid-pane">
        <EditableTable data={tableData} rows={tableRows} readonly={editingDisabled} loading={loading} foreignKeys={foreignKeys} onEdit={onEdit} onDelete={onDelete} onFollowRelation={onFollowRelation} />
      </div>
      <div className="grid-pagination table-pagination">
        <Space size={8} className="grid-pagination-summary">
          <Text type="secondary">{tablePageSummary(page, tableRows.length, rowCount)}</Text>
          {rowCount.status !== 'ready' && onCountRows && (
            <Tooltip title="对当前表执行 COUNT(*)。大表可能较慢，因此不随翻页自动统计。">
              <Button
                size="small"
                type="link"
                icon={<CalculatorOutlined />}
                loading={rowCount.status === 'loading'}
                disabled={!canCountTableRows(rowCount, Boolean(tableData), loading)}
                onClick={onCountRows}
              >
                统计总行数
              </Button>
            </Tooltip>
          )}
        </Space>
        <Space size={8} wrap={false} className="table-pagination-actions">
          <Text type="secondary">每页</Text>
          <Select
            size="small"
            className="table-page-size-select"
            value={pageSize}
            options={TABLE_PAGE_SIZE_OPTIONS.map((value) => ({ value, label: `${value} 行` }))}
            disabled={!tableData || loading || !onPageSizeChange}
            onChange={onPageSizeChange}
          />
          <Tooltip title="游标分页无法跳页，但回到第一页始终可用">
            <Button
              size="small"
              icon={<DoubleLeftOutlined />}
              aria-label="回到第一页"
              disabled={!tableData || loading || page <= 0 || !onPageChange}
              onClick={() => onPageChange?.(0)}
            >
              第一页
            </Button>
          </Tooltip>
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
            iconPlacement="end"
            disabled={!tableData || loading || !hasMore || !onPageChange}
            onClick={() => onPageChange?.(page + 1)}
          >
            下一页
          </Button>
        </Space>
      </div>
      <WorkspaceStatusBar
        status={status}
        trailing={pendingCount > 0 ? (
          <Space size={8}>
            <Badge status="warning" text={`待提交 ${pendingCount} 项`} />
            <Text type="secondary">新增 {changeSummary.inserts} · 修改 {changeSummary.updates} · 删除 {changeSummary.deletes}</Text>
          </Space>
        ) : <Text type="secondary">无待提交变更</Text>}
      />
      <Drawer
        title="变更语句预览"
        placement="bottom"
        size={DRAWER_WIDTH.form}
        open={previewOpen}
        getContainer={false}
        rootClassName="workspace-bottom-drawer"
        onClose={() => setPreviewOpen(false)}
      >
        <SqlPreview sql={previewSql} />
      </Drawer>
    </div>
  );
});
