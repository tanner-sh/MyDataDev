import { createWriteStream } from 'node:fs';
import { lstat, mkdir, mkdtemp, readlink, realpath, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { CliError, ExitCode } from '../errors.js';
import { readSqlInput, resolveConnection, type CommandContext } from './context.js';

export interface ExportOptions {
  table?: string;
  sql?: string;
  file?: string;
  schema?: string;
  exportFormat?: string;
  targetTable?: string;
  output?: string;
  bom?: boolean;
  confirmProduction?: string;
}

const EXPORT_MEDIA_TYPES: Record<string, string> = {
  csv: 'text/csv',
  json: 'application/json',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  sql: 'text/plain'
};

export async function handleExport(ctx: CommandContext, connRef: string, options: ExportOptions): Promise<void> {
  const exportFormat = (options.exportFormat || 'csv').trim().toLowerCase();
  if (!Object.hasOwn(EXPORT_MEDIA_TYPES, exportFormat)) {
    throw new CliError(
      ExitCode.USAGE_ERROR,
      'INVALID_EXPORT_FORMAT',
      `不支持的导出格式：${exportFormat}（可选 csv、json、xlsx、sql）。`
    );
  }
  if (options.table && (options.sql || options.file)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'CONFLICTING_EXPORT_SOURCE', '不能同时指定 --table 和 SQL 语句（-c / -f）。');
  }
  const targetTableParts = options.targetTable?.split('.').map((part) => part.trim());
  if (targetTableParts && (options.table || exportFormat !== 'sql' || targetTableParts.length > 3 || targetTableParts.some((part) => !part))) {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_EXPORT_TARGET', '--target-table 仅用于查询的 SQL 导出，可指定一到三段表名。');
  }

  const connection = await resolveConnection(ctx.client, connRef);
  let streamResponse;

  if (options.table) {
    const dot = options.table.indexOf('.');
    const schemaName = options.schema || (dot > 0 ? options.table.slice(0, dot) : undefined);
    const tableName = !options.schema && dot > 0 ? options.table.slice(dot + 1) : options.table;
    streamResponse = await ctx.client.requestStream('/api/data/table/export', {
      method: 'POST',
      body: {
        query: {
          connectionId: connection.id,
          schemaName,
          tableName,
          pageSize: 100
        },
        format: exportFormat
      },
      productionConfirmation: options.confirmProduction
    });
  } else {
    const sql = await readSqlInput({
      sql: options.sql,
      file: options.file,
      stdin: ctx.stdin
    });
    streamResponse = await ctx.client.requestStream('/api/sql/export', {
      method: 'POST',
      body: {
        connectionId: connection.id,
        sql,
        format: exportFormat,
        schemaName: options.schema,
        targetTableParts
      },
      productionConfirmation: options.confirmProduction
    });
  }

  const contentType = streamResponse.headers['content-type']?.split(';', 1)[0].trim().toLowerCase();
  if (contentType !== EXPORT_MEDIA_TYPES[exportFormat]) {
    streamResponse.stream.destroy();
    throw new CliError(ExitCode.EXECUTION_FAILED, 'UNEXPECTED_EXPORT_CONTENT',
      `服务端返回的内容类型 ${contentType ?? '（缺失）'} 与 ${exportFormat} 导出不匹配。`);
  }

  const stream = options.bom && exportFormat === 'csv'
    ? Readable.from(withUtf8Bom(streamResponse.stream))
    : streamResponse.stream;
  if (options.output) {
    const output = path.resolve(options.output);
    let stagingDirectory: string | undefined;
    try {
      const { target, mode } = await resolveOutputTarget(output);
      const directory = path.dirname(target);
      await mkdir(directory, { recursive: true });
      stagingDirectory = await mkdtemp(path.join(directory, '.mdd-export-'));
      const stagingFile = path.join(stagingDirectory, 'export');
      await pipeline(stream, createWriteStream(stagingFile, { mode }));
      await rename(stagingFile, target);
    } finally {
      streamResponse.stream.destroy();
      if (stagingDirectory) await rm(stagingDirectory, { recursive: true, force: true });
    }
    if (!ctx.quiet) ctx.stderr.write(`✔ 已导出到 ${output}\n`);
  } else {
    await pipeline(stream, ctx.stdout, { end: false });
  }
  if (streamResponse.headers['x-export-truncated'] === 'true') {
    ctx.stderr.write('⚠ 已达到服务端导出行数上限，结果已截断。\n');
  }
}

async function resolveOutputTarget(output: string): Promise<{ target: string; mode?: number }> {
  let target = output;
  const visited = new Set<string>();
  while (true) {
    const existing = await lstat(target).catch((error: NodeJS.ErrnoException) => {
      if (error.code !== 'ENOENT') throw error;
      return undefined;
    });
    if (!existing?.isSymbolicLink()) {
      if (existing && !existing.isFile()) {
        throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_EXPORT_PATH', `导出目标必须是普通文件：${target}`);
      }
      if (existing) return { target: await realpath(target), mode: existing.mode & 0o777 };
      if (visited.size > 0) {
        const directory = await realpath(path.dirname(target));
        return { target: path.join(directory, path.basename(target)) };
      }
      return { target };
    }
    const directory = await realpath(path.dirname(target));
    const linkPath = path.join(directory, path.basename(target));
    if (visited.has(linkPath)) {
      throw new CliError(ExitCode.USAGE_ERROR, 'EXPORT_SYMLINK_LOOP', `导出路径存在循环符号链接：${output}`);
    }
    visited.add(linkPath);
    const link = await readlink(linkPath);
    // 先由文件系统遍历目录链接，再解释目标路径中的 ..。
    target = path.isAbsolute(link) ? link : `${directory}${path.sep}${link}`;
  }
}

async function* withUtf8Bom(stream: AsyncIterable<Buffer>): AsyncGenerator<Buffer> {
  const bom = Buffer.from([0xef, 0xbb, 0xbf]);
  let prefix: Buffer | undefined = Buffer.alloc(0);
  for await (const chunk of stream) {
    if (prefix === undefined) {
      yield chunk;
      continue;
    }
    const buffered = Buffer.concat([prefix, chunk]);
    if (buffered.length < bom.length) {
      prefix = buffered;
      continue;
    }
    if (!buffered.subarray(0, bom.length).equals(bom)) yield bom;
    yield buffered;
    prefix = undefined;
  }
  if (prefix !== undefined) {
    yield bom;
    if (prefix.length > 0) yield prefix;
  }
}
