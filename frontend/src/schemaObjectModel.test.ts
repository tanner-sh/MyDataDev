import { describe, expect, it } from 'vitest';
import {
  explorerObjectCountLabel,
  explorerObjectKindLabel,
  explorerObjectKinds,
  normalizeExplorerObjectKind,
  schemaObjectCapabilities,
  schemaObjectConfirmationTarget
} from './schemaObjectModel';

describe('schema object model', () => {
  it('orders capabilities by object group', () => {
    const capabilities = schemaObjectCapabilities({
      tableBrowse: true,
      tableEdit: false,
      tableDesign: false,
      explain: false,
      nativeBackupMethods: [],
      schemaObjects: [
        { kind: 'FUNCTION', operations: ['LIST'] },
        { kind: 'VIEW', operations: ['LIST'] }
      ]
    });
    expect(capabilities.map((item) => item.kind)).toEqual(['VIEW', 'FUNCTION']);
  });

  it('uses the overload display name for strong confirmation', () => {
    expect(schemaObjectConfirmationTarget({ schemaName: 'public', name: 'calculate', displayName: 'calculate(integer)', kind: 'FUNCTION' }))
      .toBe('public.calculate(integer)');
    expect(schemaObjectConfirmationTarget({ schemaName: 'public', name: 'audit_insert', displayName: 'audit_insert · users', kind: 'TRIGGER' }))
      .toBe('public.audit_insert');
  });

  it('builds the explorer kinds from supported capabilities', () => {
    const capabilities = {
      tableBrowse: true,
      tableEdit: false,
      tableDesign: false,
      explain: false,
      nativeBackupMethods: [],
      schemaObjects: [
        { kind: 'FUNCTION' as const, operations: ['LIST' as const] },
        { kind: 'VIEW' as const, operations: ['LIST' as const] }
      ]
    };

    expect(explorerObjectKinds(capabilities)).toEqual(['TABLE', 'VIEW', 'FUNCTION']);
    expect(normalizeExplorerObjectKind('TRIGGER', capabilities)).toBe('TABLE');
    expect(normalizeExplorerObjectKind('VIEW', capabilities)).toBe('VIEW');
  });

  it('formats navigation labels and lazy counts', () => {
    expect(explorerObjectKindLabel('TABLE', true)).toBe('表');
    expect(explorerObjectKindLabel('TABLE', false)).toBe('表与视图');
    expect(explorerObjectCountLabel()).toBe('…');
    expect(explorerObjectCountLabel({ loaded: 0, loading: true })).toBe('…');
    expect(explorerObjectCountLabel({ loaded: 200, hasMore: true })).toBe('200+');
    expect(explorerObjectCountLabel({ loaded: 100, total: 257, hasMore: true })).toBe('257');
  });
});
