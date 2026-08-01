import { describe, expect, it } from 'vitest';
import { schemaObjectCapabilities, schemaObjectConfirmationTarget } from './schemaObjectModel';

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
});
