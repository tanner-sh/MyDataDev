export type ConnectionAddress = { host: string; port: number; database: string; suffix: string };
const PORTS: Record<string, number> = { mysql: 3306, mariadb: 3306, postgresql: 5432, clickhouse: 8123, 'oceanbase-mysql': 2881, 'oceanbase-oracle': 2881 };
const scheme = (type: string) => type.startsWith('oceanbase-') ? 'oceanbase' : type;
export function supportsBasicAddress(type: string) { return type in PORTS; }
export function parseConnectionAddress(type: string, url: string): ConnectionAddress | undefined {
  if (!supportsBasicAddress(type)) return undefined;
  const match = /^jdbc:([\w-]+):\/\/(\[[^\]]+\]|[^/:?;]+)(?::(\d+))?\/([^?;]*)(.*)$/.exec(url.trim());
  if (!match || match[1] !== scheme(type)) return undefined;
  return { host: match[2], port: Number(match[3] || PORTS[type]), database: match[4], suffix: match[5] };
}
export function addressProblem(address: ConnectionAddress): string | undefined {
  if (!/^(?:\[[0-9a-f:]+\]|[\p{L}\p{N}_.-]+)$/iu.test(address.host.trim())) return '请输入主机名、IPv4 或带方括号的 IPv6 地址';
  if (!Number.isInteger(address.port) || address.port < 1 || address.port > 65535) return '端口必须在 1～65535 之间';
  if (/[/?#;]/.test(address.database)) return '数据库名含地址分隔符，请使用高级 JDBC 模式';
  return undefined;
}
export function buildConnectionAddress(type: string, address: ConnectionAddress) {
  return `jdbc:${scheme(type)}://${address.host.trim()}:${address.port}/${address.database.trim()}${address.suffix}`;
}
