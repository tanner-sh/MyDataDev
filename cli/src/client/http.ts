import { readFile } from 'node:fs/promises';
import http from 'node:http';
import https from 'node:https';
import os from 'node:os';
import { CliError, ExitCode } from '../errors.js';
import type { ResolvedInstance, SystemInfo } from '../types.js';

export const EXPECTED_API_VERSION = 1;
const MAX_REDIRECTS = 3;

declare const __MDD_VERSION__: string | undefined;

export function cliVersion(): string {
  return typeof __MDD_VERSION__ === 'string' ? __MDD_VERSION__ : '1.0.0';
}

export interface RequestOptions {
  method?: string;
  headers?: Record<string, string>;
  body?: unknown;
  rawBody?: string | Buffer;
  timeoutMs?: number;
  signal?: AbortSignal;
  /** 首次探测 /api/system/info 时为 false，绝不发送 MyDataDev PAT */
  includeAuth?: boolean;
  productionConfirmation?: string;
}

export interface RawHttpResponse {
  status: number;
  headers: http.IncomingHttpHeaders;
  body: Buffer;
}

export interface StreamHttpResponse {
  status: number;
  headers: http.IncomingHttpHeaders;
  stream: http.IncomingMessage;
}

export class ApiClient {
  readonly instance: ResolvedInstance;
  readonly timeoutMs: number;
  readonly quiet: boolean;
  private readonly env: NodeJS.ProcessEnv;
  private readonly stderr: NodeJS.WritableStream;
  private systemInfoCache?: SystemInfo;
  private disabledWarned = false;
  private versionWarned = false;
  private tlsOptionsPromise?: Promise<{ ca?: Buffer; cert?: Buffer; key?: Buffer }>;

  constructor(
    instance: ResolvedInstance,
    options?: {
      timeoutMs?: number;
      quiet?: boolean;
      env?: NodeJS.ProcessEnv;
      stderr?: NodeJS.WritableStream;
    }
  ) {
    this.instance = instance;
    this.timeoutMs = options?.timeoutMs ?? 60_000;
    this.quiet = options?.quiet ?? false;
    this.env = options?.env ?? process.env;
    this.stderr = options?.stderr ?? process.stderr;
  }

  /**
   * 探测服务端版本与模式：不发送 MyDataDev PAT，防止在确认目标之前泄露令牌。
   */
  async probeSystemInfo(force = false): Promise<SystemInfo> {
    if (this.systemInfoCache && !force) return this.systemInfoCache;
    const response = await this.rawRequest('/api/system/info', {
      method: 'GET',
      includeAuth: false
    });
    if (response.status < 200 || response.status >= 300) {
      throw this.mapErrorResponse(response.status, response.body);
    }
    let info: SystemInfo;
    try {
      info = JSON.parse(response.body.toString('utf8')) as SystemInfo;
    } catch {
      throw new CliError(
        ExitCode.UNREACHABLE,
        'INVALID_BACKEND',
        `目标地址 ${this.instance.url} 不是有效的 MyDataDev 服务。`
      );
    }
    if (info.product !== 'MyDataDev' || typeof info.apiVersion !== 'number') {
      throw new CliError(
        ExitCode.UNREACHABLE,
        'INVALID_BACKEND',
        `目标地址 ${this.instance.url} 不是 MyDataDev 服务。`
      );
    }
    if (this.instance.source === 'desktop' && info.mode !== 'desktop') {
      throw new CliError(
        ExitCode.UNREACHABLE,
        'DESKTOP_NOT_RUNNING',
        `检测到端口 ${this.instance.url} 运行的不是 MyDataDev Desktop（mode=${info.mode}）。请先启动 MyDataDev。`
      );
    }
    if (info.apiVersion !== EXPECTED_API_VERSION) {
      throw new CliError(
        ExitCode.INCOMPATIBLE_VERSION,
        'INCOMPATIBLE_API_VERSION',
        `CLI (api v${EXPECTED_API_VERSION}) 与后端版本 ${info.version} (api v${info.apiVersion}) 不兼容，请升级到匹配版本。`
      );
    }
    if (!this.versionWarned && !this.quiet && minorMismatch(cliVersion(), info.version)) {
      this.versionWarned = true;
      this.stderr.write(`⚠ CLI 版本 (${cliVersion()}) 与后端版本 (${info.version}) 次版本不一致，如遇异常建议升级对齐。\n`);
    }
    if (!this.disabledWarned && !this.quiet && info.authMode === 'DISABLED' && info.mode !== 'desktop') {
      this.disabledWarned = true;
      this.stderr.write('⚠ 服务端未启用认证，任何能访问该地址的人都拥有全部权限。\n');
    }
    this.systemInfoCache = info;
    return info;
  }

  async requestJson<T>(apiPath: string, options: RequestOptions = {}): Promise<T> {
    await this.probeSystemInfo();
    const response = await this.rawRequest(apiPath, { ...options, includeAuth: options.includeAuth ?? true });
    if (response.status < 200 || response.status >= 300) {
      throw this.mapErrorResponse(response.status, response.body);
    }
    const text = response.body.toString('utf8').trim();
    if (!text) return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new CliError(ExitCode.EXECUTION_FAILED, 'INVALID_JSON', `服务端返回了非 JSON 响应（HTTP ${response.status}）。`);
    }
  }

  async requestRaw(apiPath: string, options: RequestOptions = {}): Promise<RawHttpResponse> {
    await this.probeSystemInfo();
    const response = await this.rawRequest(apiPath, { ...options, includeAuth: options.includeAuth ?? true });
    if (response.status < 200 || response.status >= 300) {
      throw this.mapErrorResponse(response.status, response.body);
    }
    return response;
  }

  async requestStream(apiPath: string, options: RequestOptions = {}): Promise<StreamHttpResponse> {
    await this.probeSystemInfo();
    const targetUrl = this.resolveApiUrl(apiPath);
    const headers = this.buildHeaders(options, true);
    const bodyBuffer = encodeBody(options, headers);
    const streamRes = await this.sendStream(targetUrl, options.method ?? 'GET', headers, bodyBuffer, options, 0);
    if (streamRes.status < 200 || streamRes.status >= 300) {
      const chunks: Buffer[] = [];
      for await (const chunk of streamRes.stream) {
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
      }
      throw this.mapErrorResponse(streamRes.status, Buffer.concat(chunks));
    }
    return streamRes;
  }

  private resolveApiUrl(apiPath: string): URL {
    if (!apiPath.startsWith('/')) {
      throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_API_PATH', `API 路径必须以 / 开头：${apiPath}`);
    }
    return new URL(`${this.instance.url}${apiPath}`);
  }

  private buildHeaders(options: RequestOptions, allowPat: boolean): Record<string, string> {
    const headers: Record<string, string> = {
      'User-Agent': `mydatadev-cli/${cliVersion()}`,
      'X-MyDataDev-Client': 'cli',
      'X-MyDataDev-Client-Host': safeClientHost()
    };

    // 网关自定义头（来自当前实例）
    if (this.instance.headers) {
      for (const [key, rawValue] of Object.entries(this.instance.headers)) {
        headers[key] = resolveHeaderValue(rawValue, this.env);
      }
    }

    // 网关 Basic Auth（来自当前实例）
    if (this.instance.basicAuth) {
      const password = this.env[this.instance.basicAuth.passwordEnv] ?? '';
      const encoded = Buffer.from(`${this.instance.basicAuth.username}:${password}`, 'utf8').toString('base64');
      headers['Authorization'] = `Basic ${encoded}`;
    }

    // PAT 鉴权：DISABLED 模式下绝不发送 MyDataDev PAT
    const authDisabled = this.systemInfoCache?.authMode === 'DISABLED';
    if (authDisabled) {
      headers['X-User'] = 'admin';
    } else if (allowPat && this.instance.token) {
      headers['Authorization'] = `Bearer ${this.instance.token}`;
    }

    if (options.productionConfirmation) {
      headers['X-Production-Confirmation'] = encodeURIComponent(options.productionConfirmation);
    }

    if (options.headers) {
      for (const [key, value] of Object.entries(options.headers)) {
        headers[key] = value;
      }
    }
    return headers;
  }

  private async loadTlsOptions(): Promise<{ ca?: Buffer; cert?: Buffer; key?: Buffer }> {
    if (!this.tlsOptionsPromise) {
      this.tlsOptionsPromise = (async () => {
        const result: { ca?: Buffer; cert?: Buffer; key?: Buffer } = {};
        if (this.instance.caFile) result.ca = await readFile(this.instance.caFile);
        if (this.instance.certFile) result.cert = await readFile(this.instance.certFile);
        if (this.instance.keyFile) result.key = await readFile(this.instance.keyFile);
        return result;
      })();
    }
    return this.tlsOptionsPromise;
  }

  private async rawRequest(apiPath: string, options: RequestOptions): Promise<RawHttpResponse> {
    const targetUrl = this.resolveApiUrl(apiPath);
    const method = (options.method ?? 'GET').toUpperCase();
    const headers = this.buildHeaders(options, options.includeAuth ?? true);
    const bodyBuffer = encodeBody(options, headers);
    const maxAttempts = method === 'GET' ? 2 : 1;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        const res = await this.sendStream(targetUrl, method, headers, bodyBuffer, options, 0);
        const chunks: Buffer[] = [];
        for await (const chunk of res.stream) {
          chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
        }
        return {
          status: res.status,
          headers: res.headers,
          body: Buffer.concat(chunks)
        };
      } catch (error) {
        if (attempt < maxAttempts && error instanceof CliError && error.exitCode === ExitCode.UNREACHABLE && !options.signal?.aborted) {
          await new Promise((resolve) => setTimeout(resolve, 200));
          continue;
        }
        throw error;
      }
    }
    throw new CliError(ExitCode.UNREACHABLE, 'NETWORK_ERROR', `无法连接到 ${this.instance.url}`);
  }

  private async sendStream(
    targetUrl: URL,
    method: string,
    headers: Record<string, string>,
    bodyBuffer: Buffer | undefined,
    options: RequestOptions,
    redirectDepth: number
  ): Promise<StreamHttpResponse> {
    const tls = targetUrl.protocol === 'https:' ? await this.loadTlsOptions() : {};
    const timeoutMs = options.timeoutMs ?? this.timeoutMs;
    const baseOrigin = new URL(this.instance.url).origin;

    return await new Promise<StreamHttpResponse>((resolve, reject) => {
      const transport = targetUrl.protocol === 'https:' ? https : http;
      const reqHeaders: Record<string, string> = { ...headers };
      if (bodyBuffer !== undefined) {
        reqHeaders['Content-Length'] = String(bodyBuffer.byteLength);
      }
      const req = transport.request(
        targetUrl,
        {
          method,
          headers: reqHeaders,
          timeout: timeoutMs,
          signal: options.signal,
          ...(targetUrl.protocol === 'https:' ? tls : {})
        },
        (res) => {
          const status = res.statusCode ?? 0;
          if ([301, 302, 303, 307, 308].includes(status) && res.headers.location && redirectDepth < MAX_REDIRECTS) {
            res.resume();
            const nextUrl = new URL(res.headers.location, targetUrl);
            // 跨源重定向绝不转发凭据与自定义网关头
            const nextHeaders = nextUrl.origin === baseOrigin ? headers : stripSensitiveHeaders(headers);
            const nextMethod = status === 303 ? 'GET' : method;
            const nextBody = status === 303 ? undefined : bodyBuffer;
            this.sendStream(nextUrl, nextMethod, nextHeaders, nextBody, options, redirectDepth + 1)
              .then(resolve, reject);
            return;
          }
          resolve({ status, headers: res.headers, stream: res });
        }
      );

      req.on('timeout', () => {
        req.destroy(new Error(`请求超时（${Math.round(timeoutMs / 1000)}s）`));
      });

      req.on('error', (error: NodeJS.ErrnoException) => {
        if (options.signal?.aborted || error.name === 'AbortError') {
          reject(new CliError(ExitCode.INTERRUPTED, 'ABORTED', '操作已取消。', undefined, { cause: error }));
          return;
        }
        const hint = this.instance.source === 'desktop'
          ? '无法连接本机 MyDataDev Desktop，请确认应用正在运行。'
          : `无法连接到 ${this.instance.url}：${error.message}`;
        reject(new CliError(ExitCode.UNREACHABLE, 'NETWORK_ERROR', hint, undefined, { cause: error }));
      });

      if (bodyBuffer !== undefined) {
        req.write(bodyBuffer);
      }
      req.end();
    });
  }

  private mapErrorResponse(status: number, body: Buffer): CliError {
    const text = body.toString('utf8').trim();
    let payload: Record<string, unknown> = {};
    if (text) {
      try {
        const parsed = JSON.parse(text) as unknown;
        if (parsed && typeof parsed === 'object') payload = parsed as Record<string, unknown>;
      } catch {
        payload = { message: text };
      }
    }
    const code = typeof payload.code === 'string' ? payload.code : `HTTP_${status}`;
    const message = typeof payload.message === 'string' && payload.message.trim()
      ? payload.message
      : `请求失败（HTTP ${status}）`;

    if (status === 401 || status === 403) {
      return new CliError(ExitCode.AUTH_ERROR, code, message, payload);
    }
    if (code === 'UNSCOPED_MUTATION_CONFIRMATION_REQUIRED') {
      return new CliError(
        ExitCode.USAGE_ERROR,
        code,
        `${message} 如确认执行，请添加 --allow-unbounded-write。`,
        payload
      );
    }
    if (code === 'PRODUCTION_CONFIRMATION_REQUIRED') {
      const expected = typeof payload.confirmationText === 'string' ? payload.confirmationText : '<连接名>';
      return new CliError(
        ExitCode.EXECUTION_FAILED,
        code,
        `${message} 请添加 --confirm-production ${expected}。`,
        payload
      );
    }
    return new CliError(ExitCode.EXECUTION_FAILED, code, message, payload);
  }
}

function encodeBody(options: RequestOptions, headers: Record<string, string>): Buffer | undefined {
  if (options.rawBody !== undefined) {
    return Buffer.isBuffer(options.rawBody) ? options.rawBody : Buffer.from(options.rawBody, 'utf8');
  }
  if (options.body !== undefined) {
    if (!hasHeader(headers, 'content-type')) {
      headers['Content-Type'] = 'application/json';
    }
    return Buffer.from(JSON.stringify(options.body), 'utf8');
  }
  return undefined;
}

function hasHeader(headers: Record<string, string>, name: string): boolean {
  const lower = name.toLowerCase();
  return Object.keys(headers).some((key) => key.toLowerCase() === lower);
}

function resolveHeaderValue(raw: string, env: NodeJS.ProcessEnv): string {
  if (raw.startsWith('env:')) {
    const varName = raw.slice(4).trim();
    return env[varName] ?? '';
  }
  return raw;
}

function stripSensitiveHeaders(headers: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(headers)) {
    const lower = key.toLowerCase();
    if (lower === 'authorization' || lower === 'cookie' || lower === 'proxy-authorization') continue;
    result[key] = value;
  }
  return result;
}

function safeClientHost(): string {
  try {
    const user = os.userInfo().username || 'unknown';
    const host = os.hostname() || 'localhost';
    return `${user}@${host}`.replace(/[^A-Za-z0-9._@:-]/g, '_').slice(0, 120);
  } catch {
    return 'unknown@localhost';
  }
}

export function minorMismatch(cliVer: string, serverVer: string): boolean {
  const a = parseMajorMinor(cliVer);
  const b = parseMajorMinor(serverVer);
  if (!a || !b) return false;
  return a[0] !== b[0] || a[1] !== b[1];
}

function parseMajorMinor(version: string): [number, number] | null {
  const match = /^v?(\d+)\.(\d+)/.exec(version.trim());
  if (!match) return null;
  return [Number(match[1]), Number(match[2])];
}
