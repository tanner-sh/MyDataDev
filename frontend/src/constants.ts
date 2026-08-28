import type { ConnectionForm, ConnectionSshForm } from './types';

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

/**
 * 抽屉宽度只有三档。
 *
 * 之前是 260/380/480/520/560/720/760/960/"large" 九种，每加一个面板就现拍一个值；
 * 同一种交互长出九种宽度，用户在面板之间切换时右侧边界一直在跳。
 *
 *   form      单栏表单、卡片列表
 *   browse    记录浏览（历史、审计、会话、任务）
 *   workspace 多区工作面板（备份、结构对比、MCP、对象管理）
 */
export const DRAWER_WIDTH = {
  form: 480,
  browse: 720,
  // 1100 而不是 960：分区导航占掉 168px，内容区只剩 733px —— 比 MCP 面板原来独占抽屉时
  // 少了近 200px，右侧的按钮和表格会被挤出去。抽屉承载的内容变多了，宽度得跟上。
  // antd 会把抽屉限制在视口宽度内，窄屏上不会溢出。
  workspace: 1100
} as const;

export const SSH_AUTH_MODE_OPTIONS = [
  { value: 'PASSWORD', label: '口令' },
  { value: 'PRIVATE_KEY', label: '私钥' }
];

export const EMPTY_SSH_FORM: ConnectionSshForm = {
  enabled: false,
  host: '',
  port: 22,
  username: '',
  authMode: 'PASSWORD',
  password: '',
  privateKey: '',
  passphrase: '',
  serverFingerprint: '',
  skipHostKeyCheck: false
};

export const EMPTY_FORM: ConnectionForm = {
  name: '',
  dbType: 'h2',
  jdbcUrl: 'jdbc:h2:mem:testdb',
  username: 'sa',
  password: '',
  environment: 'dev',
  readonly: false,
  groupName: '',
  tags: '',
  defaultSchema: '',
  initSql: '',
  description: '',
  ssh: EMPTY_SSH_FORM
};
