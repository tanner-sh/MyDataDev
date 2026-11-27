import { CliError, ExitCode } from '../errors.js';
import { formatKeyValue } from '../output/format.js';
import type { WhoAmI } from '../types.js';
import type { CommandContext } from './context.js';

export async function handleStatus(ctx: CommandContext): Promise<void> {
  const info = await ctx.client.probeSystemInfo(true);
  let whoami: WhoAmI | null = null;
  try {
    whoami = await ctx.client.requestJson<WhoAmI>('/api/auth/whoami');
  } catch (error) {
    if (ctx.client.instance.token || !(error instanceof CliError) || error.exitCode !== ExitCode.AUTH_ERROR) throw error;
  }
  if (ctx.client.instance.token && info.authMode !== 'DISABLED' && !whoami?.authenticated) {
    throw new CliError(ExitCode.AUTH_ERROR, 'INVALID_ACCESS_TOKEN', '已配置的访问令牌未通过身份验证。');
  }

  const targetText =
    ctx.client.instance.source === 'desktop'
      ? `本机 MyDataDev Desktop (${ctx.client.instance.url})`
      : `${ctx.client.instance.label} (${ctx.client.instance.url})`;

  const entries: Array<[string, unknown]> = [
    ['目标', targetText],
    ['版本', `${info.version} (api v${info.apiVersion})`],
    ['模式', info.mode],
    ['认证', info.authMode]
  ];

  if (whoami?.authenticated) {
    entries.push(['用户', whoami.username ? `${whoami.username}${whoami.role ? ` (${whoami.role})` : ''}` : '-']);
    if (whoami.authType === 'PAT') {
      entries.push(['令牌', `${whoami.tokenName ?? ''} (${whoami.tokenId ?? ''})`.trim()]);
      const scopeDesc =
        whoami.connectionScope === 'LISTED'
          ? `仅指定连接 (${(whoami.allowedConnectionIds ?? []).join(', ') || '无'})`
          : '继承账号全部连接';
      entries.push(['连接范围', scopeDesc]);
    }
  } else {
    entries.push(['用户', '未登录']);
  }

  const jsonPayload = {
    instance: {
      label: ctx.client.instance.label,
      url: ctx.client.instance.url,
      source: ctx.client.instance.source
    },
    system: info,
    identity: whoami
  };

  ctx.stdout.write(formatKeyValue(ctx.format, entries, jsonPayload) + '\n');
}
