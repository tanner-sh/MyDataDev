import { describe, expect, it } from 'vitest';
import { selectSqlTemplate } from './sqlTemplates';

describe('SQL templates', () => {
  it('uses TOP for SQL Server', () => {
    expect(selectSqlTemplate('sqlserver')).toBe('SELECT TOP 100 *\nFROM table_name;');
  });

  it('uses ROWNUM for Oracle-compatible databases', () => {
    expect(selectSqlTemplate('oracle')).toContain('ROWNUM <= 100');
    expect(selectSqlTemplate('dm')).toContain('ROWNUM <= 100');
    expect(selectSqlTemplate('oceanbase-oracle')).toContain('ROWNUM <= 100');
  });

  it('keeps LIMIT for databases that support it', () => {
    expect(selectSqlTemplate('mysql')).toContain('LIMIT 100');
    expect(selectSqlTemplate('postgresql')).toContain('LIMIT 100');
  });
});
