import readline from 'node:readline';
import { CliError, ExitCode } from '../errors.js';
import { formatErrorOutput } from '../output/format.js';
import type { Connection } from '../types.js';
import { handleConnInfo, handleConnLs, handleConnTest } from '../commands/conn.js';
import { resolveConnection, type CommandContext } from '../commands/context.js';
import {
  handleMetaDdl,
  handleMetaDescribe,
  handleMetaNamespaces,
  handleMetaObjects,
  handleMetaSearch
} from '../commands/meta.js';
import { handleSqlExec, handleSqlExplain, handleSqlQuery } from '../commands/sql.js';
import { handleStatus } from '../commands/status.js';

export interface ReplState {
  connection?: Connection;
}

/**
 * 最小交互模式（第一期）：
 * 复用单次命令的处理函数，支持 conn / meta / sql / status / help / exit，
 * 以及选定连接后直接输入以 `;` 结尾的只读 SQL 查询。
 */
export async function runRepl(ctx: CommandContext): Promise<void> {
  const info = await ctx.client.probeSystemInfo(true);
  const state: ReplState = {};
  const isInteractive = Boolean((ctx.stdin as NodeJS.ReadStream).isTTY && (ctx.stdout as NodeJS.WriteStream).isTTY);

  if (!ctx.quiet) {
    const label = ctx.client.instance.source === 'desktop'
      ? `本机 Desktop (${ctx.client.instance.url})`
      : `${ctx.client.instance.label} (${ctx.client.instance.url})`;
    ctx.stdout.write(`MyDataDev ${info.version} · ${label}\n`);
    ctx.stdout.write('输入 help 查看可用命令，输入 exit 退出。\n');
  }

  const rl = readline.createInterface({
    input: ctx.stdin,
    output: isInteractive ? ctx.stdout : undefined,
    terminal: isInteractive
  });

  const promptText = () =>
    state.connection
      ? `mdd [${ctx.client.instance.label}:${state.connection.name}]> `
      : `mdd [${ctx.client.instance.label}]> `;

  // Windows 与 POSIX 均通过 readline 的 SIGINT 事件收口退出，避免进程残留
  let closing = false;
  rl.on('SIGINT', () => {
    if (rl.line.trim().length > 0) {
      rl.write(null, { ctrl: true, name: 'u' });
      ctx.stdout.write('\n');
      if (isInteractive) rl.prompt();
      return;
    }
    closing = true;
    rl.close();
  });

  if (isInteractive) {
    rl.setPrompt(promptText());
    rl.prompt();
  }

  try {
    for await (const rawLine of rl) {
      if (closing) break;
      const line = rawLine.trim();
      if (!line) {
        if (isInteractive) {
          rl.setPrompt(promptText());
          rl.prompt();
        }
        continue;
      }
      try {
        const shouldContinue = await executeReplLine(ctx, state, line);
        if (!shouldContinue) break;
      } catch (error) {
        ctx.stderr.write(formatErrorOutput(error, ctx.format) + '\n');
      }
      if (isInteractive) {
        rl.setPrompt(promptText());
        rl.prompt();
      }
    }
  } finally {
    rl.close();
  }
}

export async function executeReplLine(
  ctx: CommandContext,
  state: ReplState,
  rawLine: string
): Promise<boolean> {
  const line = rawLine.trim();
  if (!line) return true;

  const tokens = splitShellArgs(line);
  const cmd = tokens[0].toLowerCase();

  if (cmd === 'exit' || cmd === 'quit' || cmd === '\\q') {
    return false;
  }

  if (cmd === 'help' || cmd === '?') {
    ctx.stdout.write(
      [
        '交互模式可用命令：',
        '  status                         查看当前实例与身份信息',
        '  conn ls                        列出可用连接',
        '  conn use <名称或ID>             切换当前连接',
        '  conn info [连接]                查看连接信息',
        '  conn test [连接]                测试连接',
        '  meta namespaces [连接]          列出 Schema / 命名空间',
        '  meta objects [schema]          列出对象',
        '  meta describe <对象>            查看表结构',
        '  meta ddl <对象>                 查看对象 DDL',
        '  meta search <关键词>            搜索对象与列',
        '  sql query <SQL>                执行只读查询',
        '  sql exec <SQL>                 执行写操作/DDL',
        '  sql explain <SQL>              查看执行计划',
        '  <SELECT ...;>                  选定连接后可直接输入以 ; 结尾的只读 SQL',
        '  exit                           退出'
      ].join('\n') + '\n'
    );
    return true;
  }

  if (cmd === 'status') {
    await handleStatus(ctx);
    return true;
  }

  if (cmd === 'conn') {
    const sub = (tokens[1] || '').toLowerCase();
    if (sub === 'ls' || sub === 'list') {
      await handleConnLs(ctx);
      return true;
    }
    if (sub === 'use') {
      const target = tokens[2];
      if (!target) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '用法：conn use <连接名称或ID>');
      const conn = await resolveConnection(ctx.client, target);
      state.connection = conn;
      ctx.stdout.write(`✔ 已选择连接：${conn.name} (#${conn.id}, ${conn.dbType}, ${conn.environment})\n`);
      return true;
    }
    if (sub === 'info') {
      const target = tokens[2] || state.connection?.name;
      if (!target) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选中连接，或传入连接名。');
      await handleConnInfo(ctx, target);
      return true;
    }
    if (sub === 'test') {
      const target = tokens[2] || state.connection?.name;
      if (!target) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选中连接，或传入连接名。');
      await handleConnTest(ctx, target);
      return true;
    }
    throw new CliError(ExitCode.USAGE_ERROR, 'UNKNOWN_SUBCOMMAND', `未知的 conn 子命令：${sub || '(空)'}`);
  }

  if (cmd === 'meta') {
    const sub = (tokens[1] || '').toLowerCase();
    const activeRef = state.connection?.name;
    if (sub === 'namespaces') {
      const target = tokens[2] || activeRef;
      if (!target) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选择连接。');
      await handleMetaNamespaces(ctx, target);
      return true;
    }
    if (sub === 'objects') {
      if (!activeRef) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选择连接。');
      await handleMetaObjects(ctx, activeRef, { schema: tokens[2] });
      return true;
    }
    if (sub === 'describe') {
      if (!activeRef) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选择连接。');
      if (!tokens[2]) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_OBJECT', '用法：meta describe <表名>');
      await handleMetaDescribe(ctx, activeRef, tokens[2], {});
      return true;
    }
    if (sub === 'ddl') {
      if (!activeRef) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选择连接。');
      if (!tokens[2]) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_OBJECT', '用法：meta ddl <对象名>');
      await handleMetaDdl(ctx, activeRef, tokens[2], {});
      return true;
    }
    if (sub === 'search') {
      if (!activeRef) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选择连接。');
      if (!tokens[2]) throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_KEYWORD', '用法：meta search <关键词>');
      await handleMetaSearch(ctx, activeRef, tokens[2], {});
      return true;
    }
    throw new CliError(ExitCode.USAGE_ERROR, 'UNKNOWN_SUBCOMMAND', `未知的 meta 子命令：${sub || '(空)'}`);
  }

  if (cmd === 'sql') {
    if (!state.connection) {
      throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选择连接。');
    }
    const sub = (tokens[1] || '').toLowerCase();
    const restSql = stripSqlPrefix(line, tokens[0], tokens[1] || '');
    if (sub === 'query') {
      await handleSqlQuery(ctx, String(state.connection.id), { sql: restSql, allowStdin: false, installSigint: false });
      return true;
    }
    if (sub === 'exec') {
      await handleSqlExec(ctx, String(state.connection.id), { sql: restSql, allowStdin: false, installSigint: false });
      return true;
    }
    if (sub === 'explain') {
      await handleSqlExplain(ctx, String(state.connection.id), { sql: restSql, allowStdin: false, installSigint: false });
      return true;
    }
    throw new CliError(ExitCode.USAGE_ERROR, 'UNKNOWN_SUBCOMMAND', `未知的 sql 子命令：${sub || '(空)'}`);
  }

  // 直接输入的只读 SQL（以 `;` 结尾或以常见只读关键词开头）
  if (line.endsWith(';') || /^(select|show|explain|with|values|pragma)\b/i.test(line)) {
    if (!state.connection) {
      throw new CliError(ExitCode.USAGE_ERROR, 'MISSING_CONNECTION', '请先用 `conn use <连接>` 选择连接后再执行 SQL。');
    }
    const sql = line.replace(/;\s*$/, '');
    await handleSqlQuery(ctx, String(state.connection.id), { sql, allowStdin: false, installSigint: false });
    return true;
  }

  throw new CliError(ExitCode.USAGE_ERROR, 'UNKNOWN_COMMAND', `未知命令 "${tokens[0]}"。输入 help 查看可用命令。`);
}

export function splitShellArgs(input: string): string[] {
  const args: string[] = [];
  let current = '';
  let quote: '"' | "'" | null = null;
  for (let i = 0; i < input.length; i++) {
    const ch = input[i];
    if (quote) {
      if (ch === quote) quote = null;
      else current += ch;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
    } else if (/\s/.test(ch)) {
      if (current.length > 0) {
        args.push(current);
        current = '';
      }
    } else {
      current += ch;
    }
  }
  if (current.length > 0) args.push(current);
  return args;
}

function stripSqlPrefix(line: string, first: string, second: string): string {
  const pattern = new RegExp(`^\\s*${escapeRegex(first)}\\s+${escapeRegex(second)}\\s*`, 'i');
  return line.replace(pattern, '').replace(/;\s*$/, '').trim();
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
