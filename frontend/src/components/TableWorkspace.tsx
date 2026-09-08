import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { DRAWER_WIDTH } from '../constants';
import { Badge, Button, Drawer, Dropdown, Layout, Popconfirm, Select, Space, Tooltip, Typography, Upload } from 'antd';
import type { ReactNode } from 'react';
import type { MenuProps } from 'antd';
import {
  CalculatorOutlined,
  CloudServerOutlined,
  DoubleLeftOutlined,
  DownloadOutlined,
  EyeOutlined,
  LeftOutlined,
  MoreOutlined,
  PlusOutlined,
  RedoOutlined,
  ReloadOutlined,
  RightOutlined,
  SaveOutlined,
  UndoOutlined,
  UploadOutlined
} from '@ant-design/icons';
import type { ActiveTable, ExportFormat, RowChange, TableData, TableRow, WorkspaceStatus } from '../types';
import { EditableTable } from './EditableTable';
import { SqlPreview } from './SqlPreview';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';
import { summarizeRowChanges } from '../utils';
import { SHORTCUT_HINTS } from '../keyboardShortcuts';
import { canCountTableRows, IDLE_TABLE_ROW_COUNT, tablePageSummary, type TableRowCountState } from '../tableRowCount';
import type { RelationTarget } from '../relationNavigation';
import type { TableQuery } from '../tableQuery';
import { TableQueryBuilder } from './TableQueryBuilder';

const { Header } = Layout;
const { Text } = Typography;
const TABLE_PAGE_SIZE_OPTIONS = [50, 100, 200];

/** 导出格式在工具栏下拉和「更多」子菜单里是同一份，写两遍迟早会分叉。 */
const EXPORT_MENU_ITEMS = [
  { key: 'csv', label: '导出 CSV' },
  { key: 'json', label: '导出 JSON' },
  { key: 'sql', label: '导出 SQL' },
  { key: 'xml', label: '导出 XML' },
  { key: 'markdown', label: '导出 Markdown' },
  { key: 'xlsx', label: '导出 Excel' }
];

export const TableWorkspace = memo(function TableWorkspace({
  documentTabs,
  connectionId,
  initialScrollTop, onViewScroll, onUndo, onRedo, canUndo, canRedo,
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
  tableQuery,
  onCountRows,
  onBackupTable,
  onExport,
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
  onPageSizeChange,
  onTableQueryChange
}: {
  /** 工作区标签条，渲染在工具栏最左边，兼作这个工作区的标题。 */
  documentTabs?: ReactNode;
  connectionId?: number;
  initialScrollTop?: number; onViewScroll?: (top: number) => void;
  onUndo?: () => void; onRedo?: () => void; canUndo?: boolean; canRedo?: boolean;
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
  tableQuery: TableQuery;
  onCountRows?: () => void;
  onBackupTable?: () => void;
  /** 导出当前表（含界面上的筛选与排序）；没有导出权限时不传。 */
  onExport?: (format: ExportFormat) => void;
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
  onTableQueryChange: (query: TableQuery) => void;
}) {
  const [previewOpen, setPreviewOpen] = useState(false);
  const importInputRef = useRef<HTMLInputElement>(null);
  const activeTableKey = activeTable ? `${activeTable.schemaName || ''}.${activeTable.tableName}` : '';
  const metadataSource = useMemo(() => connectionId != null && activeTable
    ? { connectionId, schemaName: activeTable.schemaName, tableName: activeTable.tableName }
    : undefined, [connectionId, activeTable?.schemaName, activeTable?.tableName]);
  const editingDisabled = readonlyConnection || !editingSupported;
  const pendingCount = pendingChanges.length;
  // App already ran buildChanges over every row to produce this list; recomputing
  // it here meant diffing the whole table twice on each keystroke.
  const changeSummary = useMemo(() => summarizeRowChanges(pendingChanges), [pendingChanges]);
  const secondaryMenu: MenuProps = {
    items: [
      { key: 'backup', icon: <CloudServerOutlined />, label: '备份此表', disabled: !activeTable || loading || !onBackupTable },
      // 导出在工具栏上自己是一个下拉，收进「更多」时得跟着进来成为子菜单 ——
      // 少了它，工具栏一收窄，导出就整个没有入口了。
      {
        key: 'export',
        icon: <DownloadOutlined />,
        label: '导出',
        disabled: !tableData || loading || !onExport,
        children: EXPORT_MENU_ITEMS.map((item) => ({ ...item, key: `export:${item.key}` }))
      },
      { key: 'reload', icon: <ReloadOutlined />, label: '刷新数据', disabled: !activeTable || loading },
      { key: 'add', icon: <PlusOutlined />, label: '新增行', disabled: !tableData || loading || editingDisabled },
      { key: 'import', icon: <UploadOutlined />, label: '导入数据', disabled: !tableData || loading || editingDisabled }
    ],
    onClick: ({ key }) => {
      if (key.startsWith('export:')) onExport?.(key.slice('export:'.length) as ExportFormat);
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
        {/* 表名由标签条给出（那里还带着「· 数据」和未提交标记），标题里不再写第二遍。 */}
        {documentTabs}
        <div className="toolbar-title">
          <Text type="secondary">
            {readonlyConnection
              ? '当前连接为只读连接'
              : !editingSupported
                ? '当前数据库方言未开放表数据编辑'
              : tableData?.editable
                ? `可编辑，行定位字段：${tableData.keyColumns.join(', ')}`
                : '当前表没有主键或全非空唯一索引，只允许新增数据'}
            {tableData?.navigationMode === 'OFFSET'
              ? tableQuery.sorts.length > 0 ? ' · 自定义排序使用偏移分页' : ' · 当前使用偏移分页，深页浏览受限'
              : ''}
          </Text>
        </div>
        {/*
          data-pending 给样式用：有待提交修改时这一行会多出「撤销全部 / 预览 / 提交」三颗
          按钮，收起浏览类操作的门槛要相应抬高。判断放在容器查询里而不是媒体查询里 ——
          真正的约束是工具栏自己有多宽，而资源管理器的宽度是用户可以拖的。
        */}
        <div className="table-toolbar-actions" data-pending={pendingCount > 0 ? 'true' : undefined}>
          <TableQueryBuilder columns={tableData?.columns || []} value={tableQuery} disabled={!tableData || loading} onApply={onTableQueryChange} />
          <Space size={8} className="table-secondary-actions">
            <Button size="small" icon={<CloudServerOutlined />} disabled={!activeTable || loading || !onBackupTable} onClick={onBackupTable}>备份此表</Button>
            {/*
              导出走的是浏览那条查询（服务端去掉分页重跑），所以导出的内容与屏幕上看到的
              筛选、排序完全一致 —— 以前要先切到 SQL 手写一条 SELECT。
            */}
            <Dropdown
              disabled={!tableData || loading || !onExport}
              menu={{
                items: EXPORT_MENU_ITEMS,
                onClick: ({ key }) => onExport?.(key as ExportFormat)
              }}
            >
              <Button size="small" icon={<DownloadOutlined />} disabled={!tableData || loading || !onExport}>导出</Button>
            </Dropdown>
            <Button size="small" icon={<ReloadOutlined />} disabled={!activeTable || loading} onClick={onReload}>刷新数据</Button>
            <Button size="small" icon={<PlusOutlined />} disabled={!tableData || loading || editingDisabled} onClick={onAddRow}>新增行</Button>
            <Upload
              accept=".csv,.xlsx,.json,.sql"
              showUploadList={false}
              disabled={!tableData || loading || editingDisabled}
              beforeUpload={(file) => {
                onImportFile(file);
                return false;
              }}
            >
              <Tooltip title="CSV / Excel / JSON / SQL。小文件直接进待提交变更，可逐行核对；Excel 与超过 10 MB 的 CSV 自动转成后台导入任务，带进度且可取消。">
                <Button size="small" icon={<UploadOutlined />} disabled={!tableData || loading || editingDisabled}>导入</Button>
              </Tooltip>
            </Upload>
          </Space>
          <Dropdown menu={secondaryMenu} trigger={['click']}>
            <Button className="table-more-actions" size="small" icon={<MoreOutlined />} aria-label="更多表格操作">更多</Button>
          </Dropdown>
          {/*
            这五个按钮只在有待提交修改时才有事可做。常驻的话，工具栏右侧四百来像素长年是
            一排灰按钮 —— 灰按钮教不了任何东西，只是噪音，还把「导出/刷新/新增行/导入」
            这些随时能用的操作挤到左边去了。没有待提交修改时底部状态栏写着「无待提交变更」，
            信息不会丢。
          */}
          {pendingCount > 0 && (
            <Space size={8} className="table-primary-actions">
              <Popconfirm
                title={`撤销全部 ${pendingCount} 项变更？`}
                description="所有尚未提交的新增、编辑和删除都会恢复。"
                okText="撤销全部"
                cancelText="保留变更"
                onConfirm={onDiscardChanges}
              >
                <Button size="small" icon={<UndoOutlined />} disabled={loading}>撤销全部</Button>
              </Popconfirm>
              <Button
                size="small"
                icon={<EyeOutlined />}
                disabled={loading || editingDisabled}
                onClick={() => {
                  setPreviewOpen(true);
                  onPreview();
                }}
              >
                预览 {pendingCount}
              </Button>
              <Tooltip title={`提交待处理的表数据变更（${SHORTCUT_HINTS.commitTableChanges}）`}>
                <Button size="small" type="primary" icon={<SaveOutlined />} disabled={loading || editingDisabled} loading={loading} onClick={onCommit}>提交 {pendingCount}</Button>
              </Tooltip>
            </Space>
          )}
          {/*
            单步撤销/重做只留图标：标签条搬进这一行之后，有待提交修改时工具栏正好放不下
            最后那颗「提交」按钮 —— 两个常用到不需要标签的操作让出这一百多像素最划算。
          */}
          {(canUndo || canRedo) && (
            <Space size={4}>
              <Tooltip title="撤销一步">
                <Button size="small" icon={<UndoOutlined />} aria-label="撤销一步" disabled={!canUndo || loading} onClick={onUndo} />
              </Tooltip>
              <Tooltip title="重做">
                <Button size="small" icon={<RedoOutlined />} aria-label="重做" disabled={!canRedo || loading} onClick={onRedo} />
              </Tooltip>
            </Space>
          )}
          <input
            ref={importInputRef}
            className="visually-hidden"
            type="file"
            accept=".csv,.xlsx,.json,.sql"
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
        <EditableTable metadataSource={metadataSource} initialScrollTop={initialScrollTop} onViewScroll={onViewScroll} data={tableData} rows={tableRows} readonly={editingDisabled} loading={loading} foreignKeys={foreignKeys} onEdit={onEdit} onDelete={onDelete} onFollowRelation={onFollowRelation} />
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
