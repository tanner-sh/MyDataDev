export type OutputFormat = 'table' | 'json' | 'ndjson' | 'csv';

export interface SystemInfo {
  product: string;
  version: string;
  apiVersion: number;
  mode: 'desktop' | 'web' | string;
  authMode: 'DISABLED' | 'LOCAL' | 'OIDC' | string;
}

export interface WhoAmI {
  authenticated: boolean;
  authType: 'SESSION' | 'PAT' | 'DISABLED' | 'NONE' | string;
  username: string | null;
  displayName: string | null;
  role: string | null;
  tokenId: string | null;
  tokenName: string | null;
  connectionScope: 'ALL' | 'LISTED' | null;
  allowedConnectionIds: number[] | null;
}

export interface Connection {
  id: number;
  name: string;
  dbType: string;
  environment: string;
  jdbcUrl: string;
  username?: string;
  readonly?: boolean;
  description?: string;
}

export interface DatabaseServerInfo {
  productName?: string;
  productVersion?: string;
  driverName?: string;
  driverVersion?: string;
  databaseName?: string;
  currentSchema?: string;
  currentUser?: string;
  charset?: string;
  timeZone?: string;
  [key: string]: unknown;
}

export interface ConnectionTestStep {
  name?: string;
  label?: string;
  ok?: boolean;
  elapsedMs?: number;
  detail?: string;
  message?: string;
}

export interface ConnectionTestResponse {
  ok: boolean;
  message: string;
  steps?: ConnectionTestStep[];
}

export interface ResultColumn {
  name: string;
  label?: string;
  typeName?: string;
}

export interface SqlResult {
  columns: ResultColumn[];
  rows: unknown[][];
  affectedRows: number;
  elapsedMs: number;
  resultSet: boolean;
  maxRows?: number;
  truncated?: boolean;
  truncationReason?: string | null;
}

export interface MetadataObjectItem {
  schemaName?: string;
  name: string;
  type: string;
  remarks?: string;
}

export interface MetadataResponse {
  dbType?: string;
  currentSchema?: string;
  schemas?: string[];
  catalogs?: string[];
  namespaces?: string[];
  objects?: MetadataObjectItem[];
  totalObjects?: number;
  page?: number;
  pageSize?: number;
  [key: string]: unknown;
}

export interface ObjectColumnInfo {
  name: string;
  typeName?: string;
  type?: string;
  size?: number;
  nullable?: boolean;
  defaultValue?: string | null;
  primaryKey?: boolean;
  remarks?: string | null;
}

export interface ObjectIndexInfo {
  name: string;
  unique?: boolean;
  columns?: string[];
}

export interface ObjectDetail {
  schemaName?: string;
  name: string;
  type: string;
  columns: ObjectColumnInfo[];
  indexes: ObjectIndexInfo[];
  primaryKeys: string[];
  primaryKeyName?: string | null;
  remarks?: string | null;
}

export interface ObjectDdlResponse {
  ddl: string;
  source?: string;
}

export interface ObjectSearchItem {
  schemaName?: string;
  name: string;
  type?: string;
  kind?: string;
  matchType?: string;
  columnName?: string;
  remarks?: string;
}

export interface ObjectSearchResponse {
  items?: ObjectSearchItem[];
  results?: ObjectSearchItem[];
  objects?: ObjectSearchItem[];
  total?: number;
  truncated?: boolean;
}

export interface ProfileBasicAuth {
  username: string;
  passwordEnv: string;
}

export interface ProfileConfig {
  url: string;
  token?: string;
  headers?: Record<string, string>;
  basicAuth?: ProfileBasicAuth;
  caFile?: string;
  certFile?: string;
  keyFile?: string;
  authMode?: string;
}

export interface CliConfigFile {
  defaultProfile?: string;
  profiles: Record<string, ProfileConfig>;
}

export interface ResolvedInstance {
  label: string;
  url: string;
  token?: string;
  headers?: Record<string, string>;
  basicAuth?: ProfileBasicAuth;
  caFile?: string;
  certFile?: string;
  keyFile?: string;
  source: 'profile' | 'flag' | 'env' | 'desktop';
}

export interface GlobalOptions {
  profile?: string;
  server?: string;
  format?: OutputFormat;
  timeout?: number;
  quiet?: boolean;
  color?: boolean;
}
