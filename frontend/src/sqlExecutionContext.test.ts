import { describe, expect, it } from 'vitest';
import { resolveSqlExecutionSchema } from './sqlExecutionContext';

describe('SQL execution context', () => {
  it('prefers the database selected in the resource explorer', () => {
    expect(resolveSqlExecutionSchema('i_fin_fi_va_db', {
      selectedSchema: 'i_fi_fi_qsjk_db',
      currentSchema: 'i_fi_fi_qsjk_db'
    })).toBe('i_fin_fi_va_db');
  });

  it('falls back to metadata and then the JDBC connection default', () => {
    expect(resolveSqlExecutionSchema('', { selectedSchema: 'archive', currentSchema: 'public' })).toBe('archive');
    expect(resolveSqlExecutionSchema('', { selectedSchema: '', currentSchema: 'public' })).toBe('public');
    expect(resolveSqlExecutionSchema('', null)).toBeUndefined();
  });
});
