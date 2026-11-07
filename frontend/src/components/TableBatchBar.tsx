import { useMemo, useState } from 'react';
import { Button, Dropdown, Input, Modal, Radio, Select, Space, Tooltip, Typography, message } from 'antd';
import { CopyOutlined, DeleteOutlined, EditOutlined, PlusSquareOutlined, UndoOutlined } from '@ant-design/icons';
import type { ResultCopyFormat, TableColumn, TableData, TableRow } from '../types';
import { cellValidation } from '../tableEditing';
import { copyText } from '../clipboard';
import { createUuid } from '../createUuid';
import {
  batchValueModes,
  canBatchEditColumn,
  duplicateRowsAsInserts,
  markRowsDeleted,
  selectedRowsCopyText,
  setColumnForRows,
  summarizeSelection
} from '../tableBatchEdit';
import type { BatchValueMode } from '../tableBatchEdit';

const { Text } = Typography;

type ValueMode = BatchValueMode;

/**
 * 勾选了行之后出现在表格上方的一条操作栏。所有操作只改待提交变更（进撤销栈、要点「提交」才落库），
 * 计算在 tableBatchEdit.ts，这里只负责按钮、弹窗和提示。
 */
export function TableBatchBar({ data, rows, selected, editingDisabled, dbType, targetTableParts, onApply, onClearSelection }: {
  data: TableData;
  rows: TableRow[];
  selected: string[];
  editingDisabled: boolean;
  dbType?: string;
  targetTableParts?: string[];
  /** 交回整份新的行；返回 false 表示被拒（比如超出单次可提交的变更数）。 */
  onApply: (rows: TableRow[]) => boolean;
  onClearSelection: () => void;
}) {
  const [messageApi, messageHolder] = message.useMessage();
  const [valueOpen, setValueOpen] = useState(false);
  const [column, setColumn] = useState<string>();
  const [mode, setMode] = useState<ValueMode>('value');
  const [text, setText] = useState('');
  const [problem, setProblem] = useState('');
  const summary = useMemo(() => summarizeSelection(rows, selected), [rows, selected]);
  const editableColumns = useMemo(() => data.columns.filter((column) => canBatchEditColumn(column, data.keyColumns)), [data.columns, data.keyColumns]);
  // 表没有行定位时，库里已有的行改不了也删不了，只有新增行能动。
  const storedLocked = !data.editable;
  const canDelete = !editingDisabled && (summary.total - summary.deleted > 0) && (!storedLocked || summary.inserted > 0);

  function copy(format: ResultCopyFormat) {
    let content: string;
    try {
      content = selectedRowsCopyText(format, data.columns, rows, selected, { dbType, targetTableParts });
    } catch (error) {
      void messageApi.error((error as Error).message);
      return;
    }
    void copyText(content).then(
      () => messageApi.success(format === 'sql' ? `已复制 ${summary.total} 行为 INSERT 语句` : `已复制 ${summary.total} 行（管道分隔，含表头）`),
      () => messageApi.error('复制失败，请手动选择内容')
    );
  }

  function remove(deleted: boolean) {
    const target = storedLocked ? rows.filter((row) => selected.includes(row.id) && row.inserted).map((row) => row.id) : selected;
    if (onApply(markRowsDeleted(rows, target, deleted)) && deleted) onClearSelection();
  }

  function duplicate() {
    const { rows: next, added } = duplicateRowsAsInserts(rows, selected, data.columns, data.keyColumns, () => `new-${createUuid()}`);
    if (added.length === 0) {
      void messageApi.info('选中的行都已标记删除，没有可复制的行');
      return;
    }
    if (!onApply(next)) return;
    const keyHint = data.keyColumns.length ? `，${data.keyColumns.join(', ')} 留空待填（原样复制必然撞主键）` : '';
    void messageApi.success(`已在顶部新增 ${added.length} 行${keyHint}`);
  }

  const valueModes = batchValueModes(data.columns.find((item) => item.name === column));

  function openValueEditor() {
    setColumn((current) => current && editableColumns.some((item) => item.name === current) ? current : editableColumns[0]?.name);
    setMode('value');
    setText('');
    setProblem('');
    setValueOpen(true);
  }

  function applyValue() {
    const target = data.columns.find((item) => item.name === column);
    if (!target) { setProblem('请选择要修改的列'); return; }
    const value = mode === 'null' ? null : mode === 'empty' ? '' : text;
    const invalid = cellValidation(target, value);
    if (invalid) { setProblem(invalid); return; }
    const result = setColumnForRows(rows, selected, target.name, value, { editable: data.editable });
    if (result.changed === 0) { setProblem('选中的行都不能修改（已标记删除，或表没有行定位字段）'); return; }
    if (!onApply(result.rows)) return;
    setValueOpen(false);
    void messageApi.success(`已把 ${result.changed} 行的 ${target.name} 设为${mode === 'null' ? ' NULL' : mode === 'empty' ? '空字符串' : `「${text}」`}${result.skipped ? `，跳过 ${result.skipped} 行（已标记删除或不可修改）` : ''}`);
  }

  return (
    <div className="table-batch-bar" role="toolbar" aria-label="批量操作选中的行">
      {messageHolder}
      <Text strong>已选 {summary.total} 行</Text>
      <Space size={4}>
        <Tooltip title={storedLocked ? '表没有主键或全非空唯一索引，只能移除选中的新增行' : '把选中的行标记为删除，提交时生成 DELETE'}>
          <Button size="small" icon={<DeleteOutlined />} disabled={!canDelete} onClick={() => remove(true)}>删除</Button>
        </Tooltip>
        {summary.deleted > 0 && (
          <Button size="small" icon={<UndoOutlined />} disabled={editingDisabled} onClick={() => remove(false)}>撤销删除 {summary.deleted}</Button>
        )}
        <Tooltip title="把选中行的某一列设为同一个值">
          <Button size="small" icon={<EditOutlined />} disabled={editingDisabled || editableColumns.length === 0 || (storedLocked && summary.inserted === 0)} onClick={openValueEditor}>设置列值</Button>
        </Tooltip>
        <Tooltip title="照着选中的行在顶部新增同样的行（主键留空）">
          <Button size="small" icon={<PlusSquareOutlined />} disabled={editingDisabled} onClick={duplicate}>复制为新增行</Button>
        </Tooltip>
        <Dropdown menu={{
          items: [
            { key: 'pipe', label: '复制为管道分隔文本（含表头）' },
            { key: 'sql', label: '复制为 INSERT 语句', disabled: !targetTableParts?.length }
          ],
          onClick: ({ key }) => copy(key as ResultCopyFormat)
        }}>
          <Button size="small" icon={<CopyOutlined />}>复制</Button>
        </Dropdown>
        <Button size="small" type="link" onClick={onClearSelection}>取消选择</Button>
      </Space>
      <Modal
        title={`设置 ${summary.total} 行的列值`}
        open={valueOpen}
        okText="应用到待提交修改"
        cancelText="取消"
        onOk={applyValue}
        onCancel={() => setValueOpen(false)}
        destroyOnHidden
      >
        <Space className="full-width" orientation="vertical" size={12}>
          <Select
            showSearch
            aria-label="要修改的列"
            style={{ width: '100%' }}
            value={column}
            onChange={(value) => {
              setColumn(value);
              setProblem('');
              // 换到的列不接受当前选的取值方式（例如从可空列换到非空列时选着 NULL）就退回「指定值」。
              if (batchValueModes(data.columns.find((item) => item.name === value))[mode]) setMode('value');
            }}
            options={editableColumns.map((item: TableColumn) => ({ value: item.name, label: `${item.name} · ${item.typeName}${item.nullable ? '' : ' · 非空'}` }))}
          />
          <Radio.Group value={mode} onChange={(event) => { setMode(event.target.value as ValueMode); setProblem(''); }}
            options={[
              { value: 'value', label: '指定值' },
              { value: 'null', label: 'NULL', disabled: Boolean(valueModes.null), title: valueModes.null },
              { value: 'empty', label: '空字符串', disabled: Boolean(valueModes.empty), title: valueModes.empty }
            ]} />
          {mode === 'value' && (
            <Input.TextArea autoSize={{ minRows: 1, maxRows: 6 }} aria-label="新的列值" value={text}
              onChange={(event) => { setText(event.target.value); setProblem(''); }} placeholder="输入新值，按原样写入（日期按库能识别的格式填）" />
          )}
          <Text type="secondary">只改待提交变更，可在预览里核对语句后再提交；已标记删除的行会跳过。</Text>
          {problem && <Text type="danger" role="alert">{problem}</Text>}
        </Space>
      </Modal>
    </div>
  );
}
