import type { ConnectionForm } from './types';

export const API = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '');

export const DB_TYPE_OPTIONS = [
  { value: 'h2', label: 'H2', url: 'jdbc:h2:mem:testdb' },
  { value: 'mysql', label: 'MySQL', url: 'jdbc:mysql://localhost:3306/demo' },
  { value: 'postgresql', label: 'PostgreSQL', url: 'jdbc:postgresql://localhost:5432/demo' },
  { value: 'oracle', label: 'Oracle', url: 'jdbc:oracle:thin:@//localhost:1521/ORCLPDB1' },
  { value: 'dm', label: '达梦', url: 'jdbc:dm://localhost:5236' },
  { value: 'oceanbase-mysql', label: 'OceanBase（MySQL 模式）', url: 'jdbc:oceanbase://localhost:2881/demo' },
  { value: 'oceanbase-oracle', label: 'OceanBase（Oracle 模式）', url: 'jdbc:oceanbase://localhost:2881/demo' },
  { value: 'sqlserver', label: 'SQL Server', url: 'jdbc:sqlserver://localhost:1433;databaseName=demo' },
  { value: 'sqlite', label: 'SQLite', url: 'jdbc:sqlite:/tmp/demo.db' },
  { value: 'mariadb', label: 'MariaDB', url: 'jdbc:mariadb://localhost:3306/demo' },
  { value: 'clickhouse', label: 'ClickHouse', url: 'jdbc:clickhouse://localhost:8123/default' }
];

export const ENVIRONMENT_OPTIONS = [
  { value: 'dev', label: '开发' },
  { value: 'test', label: '测试' },
  { value: 'prod', label: '生产' }
];

export const PASSWORD_MASK = '******';

export const EMPTY_FORM: ConnectionForm = {
  name: '本地 H2',
  dbType: 'h2',
  jdbcUrl: 'jdbc:h2:mem:testdb',
  username: 'sa',
  password: '',
  environment: 'dev',
  readonly: false
};
