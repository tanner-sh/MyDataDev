import { describe, expect, it } from 'vitest';
import {
  buildSchemaExportRequest,
  candidateKey,
  kindCounts,
  MAX_SCHEMA_EXPORT_OBJECTS,
  schemaExportFileName,
  schemaExportSummary,
  selectionProblem,
  supportsDefinerStripping,
  toggleCandidates,
  visibleCandidates
} from './schemaExport';
import type { SchemaExportCandidate } from './types';

const candidates: SchemaExportCandidate[] = [
  { kind: 'PROCEDURE', name: 'P_SYNC', displayName: 'P_SYNC', objectKey: 'k1' },
  { kind: 'TABLE', name: 'ORDERS', displayName: 'ORDERS', objectKey: null },
  { kind: 'FUNCTION', name: 'F_TOTAL', displayName: 'F_TOTAL(integer)', objectKey: 'k2' },
  { kind: 'FUNCTION', name: 'F_TOTAL', displayName: 'F_TOTAL(text)', objectKey: 'k3' },
  { kind: 'VIEW', name: 'V_ORDERS', displayName: 'V_ORDERS', objectKey: 'k4' }
];

describe('schema export', () => {
  it('keeps overloaded routines apart', () => {
    expect(candidateKey(candidates[2])).not.toBe(candidateKey(candidates[3]));
  });

  it('counts per kind in the default dependency order', () => {
    const selected = new Set([candidateKey(candidates[2])]);
    expect(kindCounts(candidates, selected).map((item) => [item.label, item.total, item.selected])).toEqual([
      ['表', 1, 0], ['视图', 1, 0], ['函数', 2, 1], ['存储过程', 1, 0]
    ]);
  });

  it('filters by kind and by name or signature, ignoring case', () => {
    expect(visibleCandidates(candidates, 'ALL', 'orders').map((item) => item.name)).toEqual(['ORDERS', 'V_ORDERS']);
    expect(visibleCandidates(candidates, 'FUNCTION', 'TEXT').map((item) => item.displayName)).toEqual(['F_TOTAL(text)']);
  });

  it('selects and clears a visible batch without touching the rest', () => {
    const start = new Set([candidateKey(candidates[0])]);
    const added = toggleCandidates(start, visibleCandidates(candidates, 'FUNCTION', ''), true);
    expect(added.size).toBe(3);
    expect(start.size).toBe(1);
    expect(toggleCandidates(added, [candidates[0]], false).has(candidateKey(candidates[0]))).toBe(false);
  });

  it('builds the request from the selection only, in candidate order', () => {
    const selected = new Set([candidateKey(candidates[4]), candidateKey(candidates[1])]);
    expect(buildSchemaExportRequest(candidates, selected, ' APP ', true)).toEqual({
      schemaName: 'APP',
      objects: [{ kind: 'TABLE', name: 'ORDERS', objectKey: null }, { kind: 'VIEW', name: 'V_ORDERS', objectKey: 'k4' }],
      stripDefiner: true
    });
    expect(buildSchemaExportRequest(candidates, selected, '  ', false).schemaName).toBeNull();
  });

  it('explains empty and oversized selections', () => {
    expect(selectionProblem(0)).toContain('至少');
    expect(selectionProblem(MAX_SCHEMA_EXPORT_OBJECTS)).toBeNull();
    expect(selectionProblem(MAX_SCHEMA_EXPORT_OBJECTS + 1)).toContain('分批');
  });

  it('offers DEFINER stripping only for the MySQL family', () => {
    expect(supportsDefinerStripping('MySQL')).toBe(true);
    expect(supportsDefinerStripping('oceanbase-mysql')).toBe(true);
    expect(supportsDefinerStripping('oracle')).toBe(false);
    expect(supportsDefinerStripping(undefined)).toBe(false);
  });

  it('makes a file name that is safe on every platform', () => {
    expect(schemaExportFileName('生产 库/A', 'YY_FBIP', '20261106-120000')).toBe('生产_库_A-YY_FBIP-objects-20261106-120000.sql');
    expect(schemaExportFileName('', '', '1')).toBe('connection-objects-1.sql');
  });

  it('leads with failures and cycles instead of the success count', () => {
    const base = { schemaName: 'APP', script: '', warnings: [], items: [{ order: 1, kind: 'TABLE', name: 'T', dependsOn: [] }] };
    expect(schemaExportSummary({ ...base, cycles: [], failed: 0 })).toEqual({ type: 'success', text: '已按依赖顺序导出 1 个对象' });
    expect(schemaExportSummary({ ...base, cycles: [['A', 'B']], failed: 2 })).toEqual({
      type: 'warning', text: '已按依赖顺序导出 1 个对象，2 个读取失败已跳过，1 处循环依赖需要人工处理'
    });
  });
});
