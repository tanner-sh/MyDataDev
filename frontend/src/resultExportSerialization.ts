import { serializeQueryResult } from './queryResultExport';
import { buildXlsx } from './xlsx';
import type { ResultExportRequest } from './types';

/** 小结果和 Worker 使用同一份序列化，保留导出本批的类型与格式语义。 */
export function serializeResultExport({ format, columns, rows, options }: ResultExportRequest): Blob {
  if (format === 'xlsx') return buildXlsx(columns, rows);
  const mime = format === 'json' ? 'application/json'
    : format === 'xml' ? 'application/xml'
    : format === 'markdown' ? 'text/markdown' : 'text/plain';
  return new Blob([serializeQueryResult(format, columns, rows, options)], { type: `${mime};charset=utf-8` });
}
