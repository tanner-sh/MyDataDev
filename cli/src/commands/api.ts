import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { CliError, ExitCode } from '../errors.js';
import type { CommandContext } from './context.js';

export interface ApiCommandOptions {
  fields?: string[];
  data?: string;
  confirmProduction?: string;
}

const ALLOWED_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD']);

/**
 * 调用当前实例上有权访问的业务 API（仅接受以 /api/ 开头的相对路径，拒绝完整 URL）。
 */
export async function handleApi(
  ctx: CommandContext,
  methodRaw: string,
  apiPath: string,
  options: ApiCommandOptions
): Promise<void> {
  const method = methodRaw.trim().toUpperCase();
  if (!ALLOWED_METHODS.has(method)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_HTTP_METHOD', `不支持的 HTTP 方法：${methodRaw}`);
  }
  validateRelativeApiPath(apiPath);

  if (options.data !== undefined && options.fields && options.fields.length > 0) {
    throw new CliError(ExitCode.USAGE_ERROR, 'CONFLICTING_API_BODY', '不能同时指定 --data 和 -f 字段参数。');
  }

  let rawBody: string | undefined = undefined;
  const headers: Record<string, string> = {};

  if (options.data !== undefined) {
    if (options.data.startsWith('@')) {
      const filePath = path.resolve(options.data.slice(1));
      try {
        rawBody = await readFile(filePath, 'utf8');
      } catch (error) {
        throw new CliError(ExitCode.USAGE_ERROR, 'FILE_READ_FAILED', `无法读取文件 ${filePath}：${(error as Error).message}`);
      }
    } else {
      rawBody = options.data;
    }
  } else if (options.fields && options.fields.length > 0) {
    const fields = new Map<string, string>();
    for (const pair of options.fields) {
      const eq = pair.indexOf('=');
      if (eq <= 0) {
        throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_FIELD', `字段参数格式应为 key=value：${pair}`);
      }
      const key = pair.slice(0, eq).trim();
      const val = pair.slice(eq + 1);
      fields.set(key, encodeFieldValue(val));
    }
    rawBody = `{${Array.from(fields, ([key, value]) => `${JSON.stringify(key)}:${value}`).join(',')}}`;
  }
  if (rawBody !== undefined) headers['Content-Type'] = 'application/json';

  const response = await ctx.client.requestRaw(apiPath, {
    method,
    headers,
    rawBody,
    productionConfirmation: options.confirmProduction
  });

  const text = response.body.toString('utf8');
  if (!text) return;
  ctx.stdout.write(text.endsWith('\n') ? text : text + '\n');
}

export function validateRelativeApiPath(apiPath: string): void {
  const trimmed = apiPath.trim();
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed) || trimmed.startsWith('//')) {
    throw new CliError(
      ExitCode.USAGE_ERROR,
      'INVALID_API_PATH',
      `mdd api 只接受当前实例下的 /api/ 相对路径，不接受完整 URL：${apiPath}`
    );
  }
  if (!trimmed.startsWith('/api/')) {
    throw new CliError(
      ExitCode.USAGE_ERROR,
      'INVALID_API_PATH',
      `mdd api 路径必须以 /api/ 开头：${apiPath}`
    );
  }
}

function encodeFieldValue(raw: string): string {
  if (raw === 'true' || raw === 'false' || raw === 'null') return raw;
  if (/^-?\d+(\.\d+)?$/.test(raw)) return raw.replace(/^(-?)0+(?=\d)/, '$1');
  if ((raw.startsWith('{') && raw.endsWith('}')) || (raw.startsWith('[') && raw.endsWith(']'))) {
    try {
      JSON.parse(raw);
      return raw;
    } catch {
      // 保持非 JSON 内容的字符串语义。
    }
  }
  return JSON.stringify(raw);
}
