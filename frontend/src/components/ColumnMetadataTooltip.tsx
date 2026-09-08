import { Tooltip } from 'antd';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { findColumnMetadata, loadObjectColumns } from '../columnMetadata';
import type { ResultColumnSource } from '../types';

type RemarksState = { key: string; text: string };

export function ColumnMetadataTooltip({ name, typeName, source, warning, children }: {
  name: string;
  typeName: string;
  source?: ResultColumnSource | null;
  warning?: string;
  children: ReactNode;
}) {
  const [remarks, setRemarks] = useState<RemarksState>();
  const version = useRef(0);
  const key = JSON.stringify(source ?? null);
  useEffect(() => () => { version.current++; }, [key]);

  async function load(open: boolean) {
    const request = ++version.current;
    if (!open || !source) return;
    setRemarks({ key, text: '正在加载备注…' });
    try {
      const object = await loadObjectColumns(source.connectionId, { schemaName: source.schemaName, name: source.tableName });
      if (request !== version.current) return;
      const column = findColumnMetadata(object.columns, source.columnName);
      setRemarks({ key, text: column ? column.remarks?.trim() || '暂无备注' : '未找到对应字段的备注' });
    } catch {
      if (request === version.current) setRemarks({ key, text: '备注暂不可用，下次悬停重试' });
    }
  }

  return <Tooltip mouseEnterDelay={0.25} onOpenChange={open => void load(open)} title={
    <div className="column-metadata-tooltip">
      <strong>{name}</strong>
      <div>类型：{typeName || '未知'}</div>
      {source && <div className="column-metadata-source">{[source.schemaName, source.tableName, source.columnName].filter(Boolean).join('.')}</div>}
      {source && <div>{remarks?.key === key ? remarks.text : '正在加载备注…'}</div>}
      {warning && <div>{warning}</div>}
    </div>
  }>{children}</Tooltip>;
}
