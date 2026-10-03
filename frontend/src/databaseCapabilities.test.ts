import { describe, expect, it } from 'vitest';
import { applyCapabilityReport, resolveLiveCapabilities, type CapabilityReport } from './databaseCapabilities';
import type { Connection } from './types';
describe('live capabilities', () => {
  it('disables confirmed unavailable operations and retains unknown permissions without mutating the source', () => {
    const connection = { capabilities: { tableBrowse: true, tableEdit: true, tableDesign: false, explain: true } } as Connection;
    const next = applyCapabilityReport(connection, { server: { product: '', version: '', driver: '', warnings: [] }, features: [
      { key: 'tableEdit', label: '', status: 'UNAVAILABLE', reason: '只读' },
      { key: 'explain', label: '', status: 'UNVERIFIED', reason: '' }
    ] });
    expect(next.capabilities).toMatchObject({ tableBrowse: true, tableEdit: false, tableDesign: false, explain: true });
    expect(connection.capabilities.tableEdit).toBe(true);
  });
});

describe('connection capability snapshots', () => {
  it('shares probe results with table documents and discards results after refresh', () => {
    const source = { id: 7, capabilities: { tableBrowse: true, tableEdit: true } } as Connection;
    const report: CapabilityReport = { server: { product: '', version: '', driver: '', warnings: [] }, features: [
      { key: 'tableEdit', label: '', status: 'UNAVAILABLE', reason: '只读' }
    ] };
    const cache = { source, report };
    expect(resolveLiveCapabilities(source, cache).capabilities.tableEdit).toBe(false);
    expect([source].map(item => resolveLiveCapabilities(item, cache))[0].capabilities.tableEdit).toBe(false);
    const refreshed = { ...source };
    expect(resolveLiveCapabilities(refreshed, cache)).toBe(refreshed);
    expect(resolveLiveCapabilities(refreshed, { source: refreshed, report: { ...report, features: [] } }).capabilities.tableEdit).toBe(true);
  });
});
