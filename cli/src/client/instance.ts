import { CliError, ExitCode } from '../errors.js';
import { loadConfig, resolveConfigPath } from '../config/profiles.js';
import type { CliConfigFile, GlobalOptions, ResolvedInstance } from '../types.js';
import { discoverDesktopInstance, type DiscoveredDesktop } from './desktop.js';

export function normalizeServerUrl(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, '');
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_SERVER_URL', `服务端地址无效：${raw}`);
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_SERVER_URL', `服务端地址只支持 http 或 https：${raw}`);
  }
  return trimmed;
}

export interface ResolveInstanceDeps {
  env?: NodeJS.ProcessEnv;
  configPath?: string;
  config?: CliConfigFile;
  discoverDesktop?: () => Promise<DiscoveredDesktop | null>;
}

/**
 * 解析当前命令绑定的唯一实例（地址 + 凭据 + 网关配置作为不可拆分的整体）。
 *
 * 优先级：
 * 1. --profile <name>（严格使用该 profile 的凭据，不混用其他来源）
 * 2. --server <url>（仅允许搭配 MDD_TOKEN，绝不继承任何 profile 的凭据）
 * 3. 环境变量 MDD_SERVER（搭配 MDD_TOKEN；单独设置 MDD_TOKEN 而不指定服务端直接报错）
 * 4. 配置文件默认 profile
 * 5. 本机 MyDataDev Desktop runtime 文件
 */
export async function resolveInstance(
  opts: GlobalOptions,
  deps: ResolveInstanceDeps = {}
): Promise<ResolvedInstance> {
  const env = deps.env ?? process.env;
  const envServer = env.MDD_SERVER?.trim() || undefined;
  const envToken = env.MDD_TOKEN?.trim() || undefined;

  if (opts.profile && opts.server) {
    throw new CliError(ExitCode.USAGE_ERROR, 'CONFLICTING_TARGET', '不能同时指定 --profile 和 --server。');
  }

  // 1) 显式 --profile
  if (opts.profile) {
    const config = deps.config ?? (await loadConfig(deps.configPath ?? resolveConfigPath()));
    const profile = config.profiles[opts.profile];
    if (!profile) {
      throw new CliError(ExitCode.USAGE_ERROR, 'PROFILE_NOT_FOUND', `找不到 profile：${opts.profile}`);
    }
    return {
      label: opts.profile,
      url: normalizeServerUrl(profile.url),
      token: profile.token,
      headers: profile.headers,
      basicAuth: profile.basicAuth,
      caFile: profile.caFile,
      certFile: profile.certFile,
      keyFile: profile.keyFile,
      source: 'profile'
    };
  }

  // 2) 显式 --server（绝不继承 profile 凭据）
  if (opts.server) {
    return {
      label: normalizeServerUrl(opts.server),
      url: normalizeServerUrl(opts.server),
      token: envToken,
      source: 'flag'
    };
  }

  // 3) 环境变量 MDD_SERVER / MDD_TOKEN
  if (envToken && !envServer) {
    throw new CliError(
      ExitCode.USAGE_ERROR,
      'TOKEN_WITHOUT_SERVER',
      '检测到环境变量 MDD_TOKEN，但未设置 MDD_SERVER 或 --server。为避免令牌发送到错误实例，请同时指定服务端地址。'
    );
  }
  if (envServer) {
    return {
      label: normalizeServerUrl(envServer),
      url: normalizeServerUrl(envServer),
      token: envToken,
      source: 'env'
    };
  }

  // 4) 默认 profile
  const config = deps.config ?? (await loadConfig(deps.configPath ?? resolveConfigPath()));
  if (config.defaultProfile && config.profiles[config.defaultProfile]) {
    const profile = config.profiles[config.defaultProfile];
    return {
      label: config.defaultProfile,
      url: normalizeServerUrl(profile.url),
      token: profile.token,
      headers: profile.headers,
      basicAuth: profile.basicAuth,
      caFile: profile.caFile,
      certFile: profile.certFile,
      keyFile: profile.keyFile,
      source: 'profile'
    };
  }

  // 5) 本机 Desktop runtime 自动发现
  const desktop = await (deps.discoverDesktop ? deps.discoverDesktop() : discoverDesktopInstance({ env }));
  if (desktop) {
    return {
      label: 'local',
      url: desktop.url,
      source: 'desktop'
    };
  }

  throw new CliError(
    ExitCode.UNREACHABLE,
    'DESKTOP_NOT_RUNNING',
    '未检测到运行中的 MyDataDev Desktop。\n请先启动 MyDataDev，或使用 `mdd login --server <url>` 连接团队服务。'
  );
}
