import path from 'node:path';
import readline from 'node:readline';
import { ApiClient } from '../client/http.js';
import { normalizeServerUrl, sameServerUrl } from '../client/instance.js';
import { loadConfig, resolveConfigPath, updateConfig } from '../config/profiles.js';
import { CliError, ExitCode } from '../errors.js';
import { formatTabular } from '../output/format.js';
import type { OutputFormat, ProfileConfig, ResolvedInstance, WhoAmI } from '../types.js';

export interface LoginOptions {
  server: string;
  profile?: string;
  tokenStdin?: boolean;
  configPath?: string;
  timeoutMs?: number;
  quiet?: boolean;
  stdout?: NodeJS.WritableStream;
  stderr?: NodeJS.WritableStream;
  stdin?: NodeJS.ReadableStream;
  env?: NodeJS.ProcessEnv;
}

export async function handleLogin(options: LoginOptions): Promise<void> {
  const stdout = options.stdout ?? process.stdout;
  const stderr = options.stderr ?? process.stderr;
  const stdin = options.stdin ?? process.stdin;
  const url = normalizeServerUrl(options.server);
  const profileName = normalizeProfileName(options.profile || deriveProfileName(url));

  const configPath = options.configPath ?? resolveConfigPath();
  const config = await loadConfig(configPath);
  const saved = config.profiles[profileName];
  const existing = saved && sameServerUrl(saved.url, url) ? saved : undefined;
  const instance: ResolvedInstance = { ...existing, label: profileName, url, token: undefined, source: 'profile' };
  const clientOptions = { timeoutMs: options.timeoutMs, quiet: options.quiet, env: options.env, stderr };
  // 仅使用同一地址已配置的网关信息；首次探测不发送 PAT。
  const info = await new ApiClient(instance, clientOptions).probeSystemInfo(true);
  const saveProfile = (token?: string) => updateConfig(configPath, (latest) => {
    const profile = latest.profiles[profileName];
    const sameTarget = profile && sameServerUrl(profile.url, url) ? profile : undefined;
    latest.profiles[profileName] = { ...sameTarget, url, token, authMode: info.authMode };
    latest.defaultProfile = profileName;
  });

  if (info.authMode === 'DISABLED') {
    await saveProfile();
    if (!options.quiet) {
      stdout.write(`✔ 已保存 profile "${profileName}"（${url}，无需令牌），已设为默认。\n`);
    }
    return;
  }

  // 2) 读取个人访问令牌（仅支持 --token-stdin 或交互式隐藏输入）
  const token = options.tokenStdin
    ? await readTokenFromStream(stdin)
    : await promptHiddenToken(stdin, stderr);

  if (!token) {
    throw new CliError(ExitCode.USAGE_ERROR, 'EMPTY_TOKEN', '访问令牌不能为空。请在 Web「访问令牌」页面创建后粘贴。');
  }
  if (!token.startsWith('mdd_pat_')) {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_TOKEN_FORMAT', '访问令牌格式不正确（应以 mdd_pat_ 开头）。');
  }

  // 3) 用该令牌校验身份
  const authedClient = new ApiClient({ ...instance, token }, clientOptions);
  const whoami = await authedClient.requestJson<WhoAmI>('/api/auth/whoami');
  if (!whoami.authenticated) {
    throw new CliError(ExitCode.AUTH_ERROR, 'INVALID_ACCESS_TOKEN', '访问令牌无效、已过期或已吊销。');
  }

  await saveProfile(token);
  if (!options.quiet) {
    stdout.write(
      `✔ 已登录 ${profileName}（${whoami.username ?? 'unknown'}${whoami.role ? `, ${whoami.role}` : ''}），已设为默认 profile。\n`
    );
  }
}

export async function handleLogout(options: {
  profile?: string;
  configPath?: string;
  quiet?: boolean;
  stdout?: NodeJS.WritableStream;
}): Promise<void> {
  const stdout = options.stdout ?? process.stdout;
  const configPath = options.configPath ?? resolveConfigPath();
  const target = await updateConfig(configPath, (config) => {
    const target = options.profile || config.defaultProfile;
    if (!target || !config.profiles[target]) {
      throw new CliError(ExitCode.USAGE_ERROR, 'PROFILE_NOT_FOUND', '未找到可注销的 profile。');
    }
    delete config.profiles[target].token;
    return target;
  });
  if (!options.quiet) {
    stdout.write(`✔ 已清除 profile "${target}" 中保存的访问令牌。\n`);
  }
}

export async function handleProfileLs(options: {
  format: OutputFormat;
  configPath?: string;
  stdout?: NodeJS.WritableStream;
}): Promise<void> {
  const stdout = options.stdout ?? process.stdout;
  const config = await loadConfig(options.configPath ?? resolveConfigPath());
  const entries = Object.entries(config.profiles);
  const rows = entries.map(([name, profile]) => [
    config.defaultProfile === name ? '*' : '',
    name,
    profile.url,
    profile.authMode === 'DISABLED' ? '免认证' : profile.token ? 'PAT 已保存' : '未配置令牌'
  ]);
  const jsonPayload = entries.map(([name, profile]) => ({
    name,
    default: config.defaultProfile === name,
    url: profile.url,
    authMode: profile.authMode ?? null,
    hasToken: Boolean(profile.token),
    hasCustomHeaders: Boolean(profile.headers && Object.keys(profile.headers).length > 0),
    hasTls: Boolean(profile.caFile || profile.certFile || profile.keyFile)
  }));
  if (entries.length === 0 && options.format === 'table') {
    stdout.write('尚未配置任何 profile（默认自动连接本机 MyDataDev Desktop）。\n');
    return;
  }
  stdout.write(formatTabular(options.format, ['默认', '名称', '目标地址', '凭据'], rows, jsonPayload) + '\n');
}

export async function handleProfileUse(
  name: string,
  options: { configPath?: string; quiet?: boolean; stdout?: NodeJS.WritableStream }
): Promise<void> {
  const stdout = options.stdout ?? process.stdout;
  const configPath = options.configPath ?? resolveConfigPath();
  const desktop = await updateConfig(configPath, (config) => {
    if (name === 'local' && !config.profiles.local) {
      config.defaultProfile = undefined;
      return true;
    }
    if (!config.profiles[name]) {
      throw new CliError(ExitCode.USAGE_ERROR, 'PROFILE_NOT_FOUND', `找不到 profile：${name}`);
    }
    config.defaultProfile = name;
    return false;
  });
  if (!options.quiet) {
    stdout.write(desktop ? '✔ 已切换回默认使用本机 MyDataDev Desktop。\n' : `✔ 已将默认 profile 切换为 "${name}"。\n`);
  }
}

export async function handleProfileRm(
  name: string,
  options: { configPath?: string; quiet?: boolean; stdout?: NodeJS.WritableStream }
): Promise<void> {
  const stdout = options.stdout ?? process.stdout;
  const configPath = options.configPath ?? resolveConfigPath();
  await updateConfig(configPath, (config) => {
    if (!config.profiles[name]) {
      throw new CliError(ExitCode.USAGE_ERROR, 'PROFILE_NOT_FOUND', `找不到 profile：${name}`);
    }
    delete config.profiles[name];
    if (config.defaultProfile === name) config.defaultProfile = undefined;
  });
  if (!options.quiet) stdout.write(`✔ 已删除 profile "${name}"。\n`);
}

export async function handleProfileSet(
  name: string,
  options: {
    headers?: string[];
    clearHeaders?: boolean;
    ca?: string;
    cert?: string;
    key?: string;
    basicUser?: string;
    basicPassEnv?: string;
    clearBasicAuth?: boolean;
    configPath?: string;
    quiet?: boolean;
    stdout?: NodeJS.WritableStream;
  }
): Promise<void> {
  const stdout = options.stdout ?? process.stdout;
  const configPath = options.configPath ?? resolveConfigPath();
  await updateConfig(configPath, (config) => {
    const profile: ProfileConfig | undefined = config.profiles[name];
    if (!profile) {
      throw new CliError(ExitCode.USAGE_ERROR, 'PROFILE_NOT_FOUND', `找不到 profile：${name}`);
    }

    if (options.clearHeaders) {
      delete profile.headers;
    }
    if (options.headers && options.headers.length > 0) {
      profile.headers = { ...(profile.headers ?? {}) };
      for (const pair of options.headers) {
        const idx = pair.indexOf('=');
        if (idx <= 0) {
          throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_HEADER', `请求头格式应为 Name=Value：${pair}`);
        }
        const key = pair.slice(0, idx).trim();
        const val = pair.slice(idx + 1).trim();
        profile.headers[key] = val;
      }
    }
    if (options.ca !== undefined) profile.caFile = options.ca ? path.resolve(options.ca) : undefined;
    if (options.cert !== undefined) profile.certFile = options.cert ? path.resolve(options.cert) : undefined;
    if (options.key !== undefined) profile.keyFile = options.key ? path.resolve(options.key) : undefined;
    if (options.clearBasicAuth) {
      delete profile.basicAuth;
    } else if (options.basicUser || options.basicPassEnv) {
      if (!options.basicUser || !options.basicPassEnv) {
        throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_BASIC_AUTH', '配置 Basic Auth 需同时提供 --basic-user 和 --basic-pass-env。');
      }
      profile.basicAuth = { username: options.basicUser, passwordEnv: options.basicPassEnv };
    }
  });
  if (!options.quiet) stdout.write(`✔ 已更新 profile "${name}"。\n`);
}

function normalizeProfileName(raw: string): string {
  const trimmed = raw.trim();
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(trimmed)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_PROFILE_NAME', `profile 名称不合法：${raw}`);
  }
  return trimmed;
}

function deriveProfileName(url: string): string {
  try {
    const parsed = new URL(url);
    return parsed.hostname.replace(/[^A-Za-z0-9._-]/g, '-').slice(0, 48) || 'default';
  } catch {
    return 'default';
  }
}

async function readTokenFromStream(stdin: NodeJS.ReadableStream): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of stdin) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString('utf8').trim();
}

async function promptHiddenToken(
  stdin: NodeJS.ReadableStream,
  stderr: NodeJS.WritableStream
): Promise<string> {
  const ttyIn = stdin as NodeJS.ReadStream;
  if (!ttyIn.isTTY) {
    return readTokenFromStream(stdin);
  }
  return await new Promise<string>((resolve, reject) => {
    stderr.write('粘贴访问令牌: ');
    const rl = readline.createInterface({ input: stdin, output: stderr, terminal: false });
    let value = '';
    const wasRaw = ttyIn.isRaw;
    ttyIn.setRawMode?.(true);
    const onData = (chunk: Buffer) => {
      const text = chunk.toString('utf8');
      for (const ch of text) {
        if (ch === '\r' || ch === '\n') {
          cleanup();
          stderr.write('\n');
          resolve(value.trim());
          return;
        }
        if (ch === '\u0003') {
          cleanup();
          stderr.write('\n');
          reject(new CliError(ExitCode.INTERRUPTED, 'ABORTED', '已取消登录。'));
          return;
        }
        if (ch === '\u007f' || ch === '\b') {
          value = value.slice(0, -1);
        } else {
          value += ch;
        }
      }
    };
    const cleanup = () => {
      ttyIn.off('data', onData);
      ttyIn.setRawMode?.(Boolean(wasRaw));
      rl.close();
    };
    ttyIn.on('data', onData);
  });
}
