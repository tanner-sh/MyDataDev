export function selectSqlTemplate(dbType?: string) {
  const normalized = dbType?.trim().toLowerCase() || '';
  if (normalized === 'sqlserver' || normalized === 'mssql' || normalized === 'sql-server') {
    return 'SELECT TOP 100 *\nFROM table_name;';
  }
  if (['oracle', 'dm', 'dameng', 'oceanbase-oracle'].includes(normalized)) {
    return 'SELECT *\nFROM table_name\nWHERE ROWNUM <= 100;';
  }
  return 'SELECT *\nFROM table_name\nLIMIT 100;';
}
