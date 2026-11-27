import { createWriteStream } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { CliError, ExitCode } from '../errors.js';
import { readSqlInput, resolveConnection, type CommandContext } from './context.js';

export interface ExportOptions {
  table?: string;
  sql?: string;
  file?: string;
  schema?: string;
  exportFormat?: string;
  output?: string;
  bom?: boolean;
  confirmProduction?: string;
}

const SUPPORTED_EXPORT_FORMATS = new Set(['csv', 'json', 'xlsx', 'sql']);

export async function handleExport(ctx: CommandContext, connRef: string, options: ExportOptions): Promise<void> {
  const exportFormat = (options.exportFormat || 'csv').trim().toLowerCase();
  if (!SUPPORTED_EXPORT_FORMATS.has(exportFormat)) {
    throw new CliError(
      ExitCode.USAGE_ERROR,
      'INVALID_EXPORT_FORMAT',
      `不支持的导出格式：${exportFormat}（可选 csv、json、xlsx、sql）。`
    );
  }
  if (options.table && (options.sql || options.file)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'CONFLICTING_EXPORT_SOURCE', '不能同时指定 --table 和 SQL 语句（-c / -f）。');
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
        schemaName: options.schema
      },
      productionConfirmation: options.confirmProduction
    });
  }

  const writeBom = Boolean(options.bom && exportFormat === 'csv');
  if (options.output) {
    const resolvedOut = path.resolve(options.output);
    await mkdir(path.dirname(resolvedOut), { recursive: true });
    const fileStream = createWriteStream(resolvedOut);
    if (writeBom) {
      fileStream.write(Buffer.from([0xef, 0xbb, 0xbf]));
    }
    await pipeline(streamResponse.stream, fileStream);
    if (!ctx.quiet) {
      const truncated = streamResponse.headers['x-export-truncated'] === 'true' ? '（已达到导出行数上限）' : '';
      ctx.stderr.write(`✔ 已导出到 ${resolvedOut}${truncated}\n`);
    }
  } else {
    if (writeBom) {
      ctx.stdout.write(Buffer.from([0xef, 0xbb, 0xbf]));
    }
    for await (const chunk of streamResponse.stream) {
      ctx.stdout.write(chunk);
    }
  }
}
