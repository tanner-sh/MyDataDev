import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { cellValidation } from '../tableEditing';
import { PanelEmpty, PanelLoading } from './PanelState';
import { fillerColumnWidth, isNumericColumnType, suggestedColumnWidth as sharedColumnWidth } from '../resultGridData';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Button, Input, Modal, Table, Tooltip, Typography } from 'antd';
import type { ColumnsType, TableRef } from 'antd/es/table';
import { DeleteOutlined, LinkOutlined, UndoOutlined } from '@ant-design/icons';
import { useTableViewportHeight } from '../hooks/useTableViewportHeight';
import type { TableColumn, TableData, TableRow } from '../types';
import { canJumpToRelation, relationJumpTooltip, type RelationTarget } from '../relationNavigation';
import {
  buildEditableDisplayRows,
  editableCellKey,
  isEditableCellDisabled,
  shouldEditableCellUpdate,
  type EditableDisplayRow
} from '../editableTableRows';

type EditableTableProps = {
  initialScrollTop?: number; onViewScroll?: (top: number) => void;
  data: TableData | null;
  rows: TableRow[];
  readonly?: boolean;
  loading?: boolean;
  /** 外键列 → 目标表，见 relationNavigation.ts。 */
  foreignKeys?: Map<string, RelationTarget>;
  onEdit: (rowId: string, column: string, value: unknown) => void;
  onDelete: (rowId: string) => void;
  onFollowRelation?: (target: RelationTarget, value: unknown) => void;
};

export const EditableTable = memo(function EditableTable({ initialScrollTop = 0, onViewScroll, data, rows, readonly = false, loading = false, foreignKeys, onEdit, onDelete, onFollowRelation }: EditableTableProps) {
  const tableRef = useRef<TableRef>(null);
  const lastScrolledDataRef = useRef<TableData | null>(null);
  const [largeEditor, setLargeEditor] = useState<{ rowId: string; column: TableColumn; value: string }>();
  const [largeError, setLargeError] = useState('');
  const [activeCell, setActiveCell] = useState<string | null>(null);
  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({});
  const { viewportRef, scrollY, viewportWidth } = useTableViewportHeight({ enabled: Boolean(data) });

  useEffect(() => {
    setActiveCell(null);
    setColumnWidths({});
  }, [data]);

  // Everything a cell renders from is folded onto the record here, so
  // shouldEditableCellUpdate can skip untouched cells even though antd rebuilds
  // the column definitions on every activation and every resize pointer move.
  const displayRows = useMemo(
    () => buildEditableDisplayRows({ rows, data, readonly, loading, activeCell }),
    [activeCell, data, loading, readonly, rows]
  );
  const suggestedWidths = useMemo(() => new Map((data?.columns || []).map((column) => [column.name, suggestedColumnWidth(column, data?.rows || [])])), [data]);
  // Precomputed on the record, so antd does not re-diff every row's values on
  // each render just to decide a class name.
  const rowClassName = useCallback((row: EditableDisplayRow) => row.rowClassName, []);
  const setColumnWidth = useCallback((columnName: string, width: number) => {
    setColumnWidths((current) => ({ ...current, [columnName]: Math.max(88, Math.min(520, width)) }));
  }, []);
  const beginResize = useCallback((event: ReactMouseEvent, columnName: string, initialWidth: number) => {
    event.preventDefault();
    event.stopPropagation();
    const startX = event.clientX;
    const move = (moveEvent: MouseEvent) => setColumnWidth(columnName, initialWidth + moveEvent.clientX - startX);
    const stop = () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', stop);
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', stop);
  }, [setColumnWidth]);

  const columns = useMemo<ColumnsType<EditableDisplayRow>>(() => {
    if (!data) return [];
    return [
      {
        title: '操作',
        key: 'action',
        fixed: 'left',
        width: 58,
        align: 'center',
        shouldCellUpdate: shouldEditableCellUpdate,
        render: (_, row, rowIndex) => (
          <Tooltip title={row.deleted ? '撤销删除' : row.inserted ? '移除新增行' : '标记为删除'}>
            <Button
              size="small"
              type="text"
              // 不再默认用 danger：一屏 100 行就是 100 个红色图标，整屏最强的颜色给了
              // 最低频、最危险的操作。改成中性色，hover/focus 时才变红（见 styles.css）。
              className={row.deleted ? 'editable-row-action is-undo' : 'editable-row-action'}
              icon={row.deleted ? <UndoOutlined /> : <DeleteOutlined />}
              disabled={row.rowDisabled}
              aria-label={row.deleted ? `撤销删除第 ${rowIndex + 1} 行` : row.inserted ? `移除新增的第 ${rowIndex + 1} 行` : `标记删除第 ${rowIndex + 1} 行`}
              onClick={() => onDelete(row.id)}
            />
          </Tooltip>
        )
      },
      ...data.columns.map((column) => ({
        title: (
          <div className="resizable-column-title" title={column.truncated ? `${column.typeName} · 本页存在超长值，已截断并禁用该列编辑` : column.typeName}>
            <span>{column.name}{column.truncated ? ' ⚠' : ''}</span>
            <span
              className="column-resize-handle"
              role="separator"
              aria-orientation="vertical"
              aria-label={`调整 ${column.name} 列宽`}
              tabIndex={0}
              onMouseDown={(event) => beginResize(event, column.name, columnWidths[column.name] || suggestedWidths.get(column.name) || 156)}
              onDoubleClick={() => setColumnWidths((current) => {
                const next = { ...current };
                delete next[column.name];
                return next;
              })}
              onKeyDown={(event) => {
                if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
                event.preventDefault();
                const current = columnWidths[column.name] || suggestedWidths.get(column.name) || 156;
                setColumnWidth(column.name, current + (event.key === 'ArrowRight' ? 12 : -12));
              }}
            />
          </div>
        ),
        key: column.name,
        width: columnWidths[column.name] || suggestedWidths.get(column.name),
        // 与查询结果表一致：数值列靠右。
        className: isNumericColumnType(column.typeName) ? 'numeric-column' : undefined,
        ellipsis: true,
        shouldCellUpdate: shouldEditableCellUpdate,
        render: (_: unknown, row: EditableDisplayRow, rowIndex: number) => {
          const cellKey = editableCellKey(row.id, column.name);
          const relation = foreignKeys?.get(column.name);
          return (
            <EditableCell
              relation={relation}
              onFollowRelation={onFollowRelation}
              rowId={row.id}
              rowNumber={rowIndex + 1}
              column={column}
              value={row.values[column.name]}
              inserted={Boolean(row.inserted)}
              touched={!row.inserted || Boolean(row.touchedColumns?.includes(column.name))}
              disabled={isEditableCellDisabled(row, column)}
              editing={row.editingColumn === column.name}
              onActivate={() => setActiveCell(cellKey)}
              onDeactivate={() => setActiveCell((current) => current === cellKey ? null : current)}
              onCommit={onEdit}
              onExpand={value => { setLargeError(''); setLargeEditor({ rowId: row.id, column, value }); }}
            />
          );
        }
      }))
    ];
  // activeCell, readonly and loading are deliberately absent: they reach the
  // cells through the record, which is what shouldCellUpdate compares.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [beginResize, columnWidths, data, foreignKeys, onDelete, onEdit, onFollowRelation, setColumnWidth, suggestedWidths]);

  useLayoutEffect(() => {
    if (!data || scrollY === undefined || !tableRef.current) return;
    if (lastScrolledDataRef.current === data) return;
    tableRef.current.scrollTo({ top: initialScrollTop });
    lastScrolledDataRef.current = data;
  }, [data, scrollY]);

  if (!data) return <PanelEmpty title="点击左侧对象树中的表来浏览数据" fill />;

  const contentWidth = data.columns.reduce((total, column) => total + (columnWidths[column.name] || suggestedWidths.get(column.name) || 160), 58);
  // 列宽之和填不满视口时补一列空白，让各列保住按内容估出的宽度（见 fillerColumnWidth）。
  const filler = fillerColumnWidth(contentWidth, viewportWidth);
  const scrollX = contentWidth + filler;
  const gridColumns = filler > 0
    ? [...columns, { title: '', key: '__filler', width: filler, className: 'grid-filler-column', render: () => null }]
    : columns;
  return (
    <div ref={viewportRef} className="editable-table-viewport">
      <Modal title={`编辑 ${largeEditor?.column.name || ''}`} open={Boolean(largeEditor)} onCancel={() => setLargeEditor(undefined)} width={720} okText="应用到待提交修改" cancelText="取消" onOk={() => {
        if (!largeEditor) return;
        const problem = cellValidation(largeEditor.column, largeEditor.value);
        if (problem) { setLargeError(problem); return; }
        onEdit(largeEditor.rowId, largeEditor.column.name, largeEditor.value);
        setLargeEditor(undefined);
      }}>
        <Input.TextArea rows={14} aria-label="长文本或 JSON 内容" value={largeEditor?.value || ''} onChange={event => setLargeEditor(editor => editor ? { ...editor, value: event.target.value } : undefined)} />
        <Typography.Text type="danger" role="alert">{largeError}</Typography.Text>
        {/json/i.test(largeEditor?.column.typeName || '') && <Button onClick={() => {
          try { setLargeEditor(editor => editor ? { ...editor, value: JSON.stringify(JSON.parse(editor.value), null, 2) } : undefined); setLargeError(''); }
          catch { setLargeError('JSON 格式不正确，请检查引号、逗号与括号'); }
        }}>格式化 JSON</Button>}
      </Modal>
      {scrollY === undefined ? (
        <PanelLoading compact text="正在准备表格…" />
      ) : (
        <Table<EditableDisplayRow>
          ref={tableRef}
          size="small"
          className="data-grid data-grid-fill editable-grid"
          columns={gridColumns}
          dataSource={displayRows}
          loading={loading}
          rowKey="id"
          onScroll={event => onViewScroll?.(event.currentTarget.scrollTop)}
          pagination={false}
          virtual
          rowClassName={rowClassName}
          scroll={{ x: scrollX, y: scrollY }}
        />
      )}
    </div>
  );
});

const EditableCell = memo(function EditableCell({ rowId, rowNumber, column, value, inserted, touched, disabled, editing, relation, onActivate, onDeactivate, onCommit, onExpand, onFollowRelation }: {
  rowId: string;
  rowNumber: number;
  column: TableColumn;
  value: unknown;
  inserted: boolean;
  touched: boolean;
  disabled: boolean;
  editing: boolean;
  relation?: RelationTarget;
  onActivate: () => void;
  onDeactivate: () => void;
  onExpand: (value: string) => void;
  onCommit: (rowId: string, column: string, value: unknown) => void;
  onFollowRelation?: (target: RelationTarget, value: unknown) => void;
}) {
  const normalizedValue = String(value ?? '');
  const [draft, setDraft] = useState(normalizedValue);
  const inputRef = useRef<React.ComponentRef<typeof Input>>(null);
  const mode = inserted && !touched ? 'default' : value == null ? 'null' : 'value';

  useEffect(() => setDraft(normalizedValue), [column.name, normalizedValue, rowId, touched]);

  useEffect(() => {
    if (!editing) return;
    const timer = requestAnimationFrame(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    });
    return () => cancelAnimationFrame(timer);
  }, [editing]);

  const commit = () => {
    if (draft !== normalizedValue && (mode === 'value' || draft.length > 0)) onCommit(rowId, column.name, draft);
    onDeactivate();
  };

  if (!editing) {
    const displayValue = mode === 'null' ? 'NULL' : mode === 'default' ? 'DEFAULT' : normalizedValue;
    return (
      <div
        className={`editable-cell-display editable-cell-${mode}${disabled ? ' is-disabled' : ''}`}
        title={displayValue}
        tabIndex={disabled ? undefined : 0}
        role={disabled ? undefined : 'button'}
        aria-label={`${column.name}，第 ${rowNumber} 行，${displayValue || '空字符串'}${disabled ? '' : '，按回车编辑'}`}
        onClick={disabled ? undefined : onActivate}
        onKeyDown={disabled ? undefined : (event) => {
          if (event.key === 'Enter' || event.key === 'F2') {
            event.preventDefault();
            onActivate();
          }
        }}
      >
        {mode === 'null' || mode === 'default'
          ? <span className="editable-cell-state">{displayValue}</span>
          : <span className="editable-cell-value">{normalizedValue || <span className="editable-cell-empty">空字符串</span>}</span>}
        {relation && onFollowRelation && (
          <Tooltip title={relationJumpTooltip(relation, value)}>
            <button
              type="button"
              className="editable-cell-relation"
              aria-label={relationJumpTooltip(relation, value)}
              disabled={!canJumpToRelation(value)}
              onClick={(event) => {
                event.stopPropagation();
                onFollowRelation(relation, value);
              }}
            >
              <LinkOutlined />
            </button>
          </Tooltip>
        )}
      </div>
    );
  }

  return (
    <div className="editable-cell-control">
      <Button size="small" onMouseDown={event => event.preventDefault()} onClick={() => onExpand(draft)} aria-label={`展开编辑 ${column.name}`}>展开</Button>
      <Input
        ref={inputRef}
        size="small"
        value={draft}
        placeholder={mode === 'default' ? 'DEFAULT' : mode === 'null' ? 'NULL' : undefined}
        aria-label={`编辑 ${column.name}，第 ${rowNumber} 行`}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={commit}
        onPressEnter={(event) => event.currentTarget.blur()}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault();
            setDraft(normalizedValue);
            onDeactivate();
          }
        }}
      />
      {inserted && touched && (
        <Tooltip title="使用数据库默认值">
          <Button size="small" type="text" aria-label={`将第 ${rowNumber} 行的 ${column.name} 恢复为默认值`} onMouseDown={(event) => event.preventDefault()} onClick={() => { onCommit(rowId, column.name, undefined); onDeactivate(); }}>默认</Button>
        </Tooltip>
      )}
      {column.nullable && (
        <Tooltip title={mode === 'null' ? '改为空字符串' : '设为 NULL'}>
          <Button size="small" type="text" aria-label={`将第 ${rowNumber} 行的 ${column.name} ${mode === 'null' ? '改为空字符串' : '设为 NULL'}`} onMouseDown={(event) => event.preventDefault()} onClick={() => { onCommit(rowId, column.name, mode === 'null' ? '' : null); onDeactivate(); }}>{mode === 'null' ? '空串' : 'NULL'}</Button>
        </Tooltip>
      )}
    </div>
  );
});

/**
 * 与查询结果表共用同一套估宽规则，两张表的列宽不该有两种算法。
 *
 * 唯一的差别是表头余量：结果表的表头里有排序和筛选两个按钮要占位置，这张表的表头只有
 * 一条列宽拖动线（压在内边距上，不额外占宽），所以余量传 0。
 */
function suggestedColumnWidth(column: TableColumn, rows: Record<string, unknown>[]) {
  return sharedColumnWidth(column.name, column.typeName, rows.map((row) => row[column.name]));
}

