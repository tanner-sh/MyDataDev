import { readFile } from 'node:fs/promises';
import path from 'node:path';
import type { ApiClient } from '../client/http.js';
import { CliError, ExitCode } from '../errors.js';
import type { Connection, OutputFormat } from '../types.js';

export interface CommandContext {
  client: ApiClient;
  format: OutputFormat;
  quiet: boolean;
  stdout: NodeJS.WritableStream;
  stderr: NodeJS.WritableStream;
  stdin: NodeJS.ReadableStream;
  configPath?: string;
}

export async function resolveConnection(client: ApiClient, ref: string): Promise<Connection> {
  const trimmed = ref.trim();
  if (!trimmed) {
    throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请指定连接名称或 ID。');
  }
  const connections = await client.requestJson<Connection[]>('/api/connections');
  if (/^\d+$/.test(trimmed)) {
    const id = Number(trimmed);
    const byId = connections.find((c) => c.id === id);
    if (byId) return byId;
  }
  const exact = connections.filter((c) => c.name === trimmed);
  if (exact.length === 1) return exact[0];
  if (exact.length > 1) {
    const list = exact.map((c) => `  #${c.id} ${c.name} (${c.dbType}, ${c.environment})`).join('\n');
    throw new CliError(
      ExitCode.USAGE_ERROR,
      'AMBIGUOUS_CONNECTION',
      `存在多个同名连接 "${trimmed}"，请改用数字 ID：\n${list}`
    );
  }
  throw new CliError(
    ExitCode.EXECUTION_FAILED,
    'CONNECTION_NOT_FOUND',
    `找不到连接 "${trimmed}"。可运行 \`mdd conn ls\` 查看当前可用连接。`
  );
}

export async function readSqlInput(options: {
  sql?: string;
  file?: string;
  stdin: NodeJS.ReadableStream;
  allowStdin?: boolean;
}): Promise<string> {
  if (options.sql && options.file) {
    throw new CliError(ExitCode.USAGE_ERROR, 'CONFLICTING_SQL_INPUT', '不能同时指定 -c 和 -f。');
  }
  if (options.sql) {
    const trimmed = options.sql.trim();
    if (!trimmed) throw new CliError(ExitCode.USAGE_ERROR, 'EMPTY_SQL', 'SQL 语句不能为空。');
    return trimmed;
  }
  if (options.file) {
    const resolved = path.resolve(options.file);
    try {
      const content = (await readFile(resolved, 'utf8')).trim();
      if (!content) throw new CliError(ExitCode.USAGE_ERROR, 'EMPTY_SQL', `SQL 文件为空：${resolved}`);
      return content;
    } catch (error) {
      if (error instanceof CliError) throw error;
      throw new CliError(ExitCode.USAGE_ERROR, 'FILE_READ_FAILED', `无法读取文件 ${resolved}：${(error as Error).message}`);
    }
  }
  const stdinIsTty = Boolean((options.stdin as NodeJS.ReadStream).isTTY);
  if (options.allowStdin !== false && !stdinIsTty) {
    const chunks: Buffer[] = [];
    for await (const chunk of options.stdin) {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
    }
    const content = Buffer.concat(chunks).toString('utf8').trim();
    if (content) return content;
  }
  throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_SQL', '请通过 -c <SQL>、-f <文件> 或标准输入提供 SQL 语句。');
}
