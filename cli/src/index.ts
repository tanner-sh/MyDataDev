import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { Command, CommanderError } from 'commander';
import { ApiClient, cliVersion } from './client/http.js';
import { resolveInstance, type ResolveInstanceDeps } from './client/instance.js';
import { handleApi } from './commands/api.js';
import { handleConnInfo, handleConnLs, handleConnTest } from './commands/conn.js';
import type { CommandContext } from './commands/context.js';
import { handleExport } from './commands/export.js';
import {
  handleLogin,
  handleLogout,
  handleProfileLs,
  handleProfileRm,
  handleProfileSet,
  handleProfileUse
} from './commands/login.js';
import {
  handleMetaDdl,
  handleMetaDescribe,
  handleMetaNamespaces,
  handleMetaObjects,
  handleMetaSearch
} from './commands/meta.js';
import { handleSqlExec, handleSqlExplain, handleSqlQuery } from './commands/sql.js';
import { handleStatus } from './commands/status.js';
import { CliError, ExitCode } from './errors.js';
import { formatErrorOutput, resolveOutputFormat } from './output/format.js';
import { runRepl } from './repl/index.js';
import type { GlobalOptions, OutputFormat } from './types.js';

export interface RunCliIo {
  stdout?: NodeJS.WritableStream;
  stderr?: NodeJS.WritableStream;
  stdin?: NodeJS.ReadableStream;
  isStdoutTty?: boolean;
  isStdinTty?: boolean;
  env?: NodeJS.ProcessEnv;
  configPath?: string;
  instanceDeps?: ResolveInstanceDeps;
}

const VALID_FORMATS = new Set<OutputFormat>(['table', 'json', 'ndjson', 'csv']);

function parseOutputFormat(raw: string | undefined): OutputFormat | undefined {
  if (!raw) return undefined;
  const lower = raw.trim().toLowerCase() as OutputFormat;
  if (!VALID_FORMATS.has(lower)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_FORMAT', `不支持的输出格式：${raw}（可选 table、json、ndjson、csv）。`);
  }
  return lower;
}

function parsePositiveInt(raw: string | undefined, flagName: string): number | undefined {
  if (raw === undefined) return undefined;
  const num = Number(raw);
  if (!Number.isInteger(num) || num <= 0) {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_NUMBER', `${flagName} 必须是正整数：${raw}`);
  }
  return num;
}

export async function runCli(argv: string[], io: RunCliIo = {}): Promise<number> {
  const stdout = io.stdout ?? process.stdout;
  const stderr = io.stderr ?? process.stderr;
  const stdin = io.stdin ?? process.stdin;
  const isStdoutTty = io.isStdoutTty ?? Boolean((stdout as NodeJS.WriteStream).isTTY);
  const isStdinTty = io.isStdinTty ?? Boolean((stdin as NodeJS.ReadStream).isTTY);
  const env = io.env ?? process.env;

  const program = new Command();
  program
    .name('mdd')
    .description('MyDataDev 命令行工具（支持单次命令与交互模式）')
    .version(cliVersion(), '-v, --version', '显示版本号')
    .option('--profile <name>', '指定配置 profile')
    .option('--server <url>', '指定服务端地址（仅搭配 MDD_TOKEN，不继承 profile 凭据）')
    .option('--format <format>', '输出格式：table | json | ndjson | csv')
    .option('--timeout <seconds>', '请求超时秒数（默认 60）')
    .option('-q, --quiet', '静默模式，不输出辅助提示')
    .option('--no-color', '禁用颜色输出')
    .configureOutput({
      writeOut: (str) => stdout.write(str),
      writeErr: (str) => stderr.write(str)
    })
    .exitOverride();

  const buildContext = async (): Promise<CommandContext> => {
    const rawOpts = program.opts<{
      profile?: string;
      server?: string;
      format?: string;
      timeout?: string;
      quiet?: boolean;
      color?: boolean;
    }>();
    const format = resolveOutputFormat(parseOutputFormat(rawOpts.format), isStdoutTty);
    const timeoutSec = parsePositiveInt(rawOpts.timeout, '--timeout');
    const globalOpts: GlobalOptions = {
      profile: rawOpts.profile,
      server: rawOpts.server,
      format,
      timeout: timeoutSec ? timeoutSec * 1000 : undefined,
      quiet: Boolean(rawOpts.quiet),
      color: rawOpts.color
    };
    const instance = await resolveInstance(globalOpts, {
      env,
      configPath: io.configPath,
      ...io.instanceDeps
    });
    const client = new ApiClient(instance, {
      timeoutMs: globalOpts.timeout,
      quiet: globalOpts.quiet,
      env,
      stderr
    });
    return {
      client,
      format,
      quiet: Boolean(rawOpts.quiet),
      stdout,
      stderr,
      stdin,
      configPath: io.configPath
    };
  };

  // 无参数直接运行 `mdd`：终端下进入交互模式，否则输出帮助
  program.action(async () => {
    if (isStdinTty && isStdoutTty) {
      const ctx = await buildContext();
      await runRepl(ctx);
      return;
    }
    program.outputHelp();
  });

  program
    .command('repl')
    .description('进入交互模式（支持连接切换、元数据和 SQL 查询）')
    .action(async () => {
      const ctx = await buildContext();
      await runRepl(ctx);
    });

  program
    .command('status')
    .description('显示当前目标实例、服务端版本与登录身份')
    .action(async () => {
      await handleStatus(await buildContext());
    });

  program
    .command('login')
    .description('登录团队 Web 服务（粘贴个人访问令牌；免认证部署自动跳过令牌）')
    .requiredOption('--server <url>', '服务端地址')
    .option('--profile <name>', '保存的 profile 名称')
    .option('--token-stdin', '从标准输入读取访问令牌')
    .action(async (cmdOpts: { server: string; profile?: string; tokenStdin?: boolean }) => {
      const rootOpts = program.opts<{ timeout?: string; quiet?: boolean }>();
      const timeoutSec = parsePositiveInt(rootOpts.timeout, '--timeout');
      await handleLogin({
        server: cmdOpts.server,
        profile: cmdOpts.profile,
        tokenStdin: cmdOpts.tokenStdin,
        configPath: io.configPath,
        timeoutMs: timeoutSec ? timeoutSec * 1000 : undefined,
        quiet: Boolean(rootOpts.quiet),
        stdout,
        stderr,
        stdin
      });
    });

  program
    .command('logout')
    .description('清除本地保存的访问令牌')
    .option('--profile <name>', '目标 profile 名称')
    .action(async (cmdOpts: { profile?: string }) => {
      const rootOpts = program.opts<{ quiet?: boolean; profile?: string }>();
      await handleLogout({
        profile: cmdOpts.profile || rootOpts.profile,
        configPath: io.configPath,
        quiet: Boolean(rootOpts.quiet),
        stdout
      });
    });

  const profileCmd = program.command('profile').description('管理实例 profile 配置');
  profileCmd
    .command('ls')
    .description('列出已保存的 profile')
    .action(async () => {
      const rootOpts = program.opts<{ format?: string }>();
      const format = resolveOutputFormat(parseOutputFormat(rootOpts.format), isStdoutTty);
      await handleProfileLs({ format, configPath: io.configPath, stdout });
    });
  profileCmd
    .command('use <name>')
    .description('切换默认 profile（传 local 可切回默认连接本机 Desktop）')
    .action(async (name: string) => {
      const rootOpts = program.opts<{ quiet?: boolean }>();
      await handleProfileUse(name, { configPath: io.configPath, quiet: Boolean(rootOpts.quiet), stdout });
    });
  profileCmd
    .command('rm <name>')
    .description('删除指定 profile')
    .action(async (name: string) => {
      const rootOpts = program.opts<{ quiet?: boolean }>();
      await handleProfileRm(name, { configPath: io.configPath, quiet: Boolean(rootOpts.quiet), stdout });
    });
  profileCmd
    .command('set <name>')
    .description('配置 profile 的网关请求头、Basic Auth 或自定义 TLS/mTLS 证书')
    .option('--header <k=v>', '添加自定义请求头（值支持 env:VAR_NAME）', (v, acc: string[]) => [...acc, v], [])
    .option('--clear-headers', '清空自定义请求头')
    .option('--ca <file>', '自定义 CA 证书文件')
    .option('--cert <file>', 'mTLS 客户端证书文件')
    .option('--key <file>', 'mTLS 客户端私钥文件')
    .option('--basic-user <username>', '网关 Basic Auth 用户名')
    .option('--basic-pass-env <envVar>', '网关 Basic Auth 密码所在环境变量名')
    .option('--clear-basic-auth', '清除 Basic Auth 配置')
    .action(async (name: string, cmdOpts) => {
      const rootOpts = program.opts<{ quiet?: boolean }>();
      await handleProfileSet(name, {
        headers: cmdOpts.header,
        clearHeaders: cmdOpts.clearHeaders,
        ca: cmdOpts.ca,
        cert: cmdOpts.cert,
        key: cmdOpts.key,
        basicUser: cmdOpts.basicUser,
        basicPassEnv: cmdOpts.basicPassEnv,
        clearBasicAuth: cmdOpts.clearBasicAuth,
        configPath: io.configPath,
        quiet: Boolean(rootOpts.quiet),
        stdout
      });
    });

  const connCmd = program.command('conn').description('查看与测试数据库连接');
  connCmd
    .command('ls')
    .description('列出当前有权访问的数据库连接')
    .action(async () => {
      await handleConnLs(await buildContext());
    });
  connCmd
    .command('info <conn>')
    .description('查看连接详情与服务端版本')
    .action(async (conn: string) => {
      await handleConnInfo(await buildContext(), conn);
    });
  connCmd
    .command('test <conn>')
    .description('测试数据库连接连通性')
    .action(async (conn: string) => {
      await handleConnTest(await buildContext(), conn);
    });

  const sqlCmd = program.command('sql').description('执行只读查询、写语句与执行计划');
  sqlCmd
    .command('query <conn>')
    .description('执行单条只读查询（服务端强制只读并拒绝写操作）')
    .option('-c, --sql <sql>', 'SQL 语句')
    .option('-f, --file <file>', 'SQL 文件路径')
    .option('--schema <schema>', '默认 Schema / 数据库')
    .option('--max-rows <n>', '最大返回行数')
    .option('--confirm-production <name>', '生产连接二次确认（需与连接名完全一致）')
    .action(async (conn: string, cmdOpts) => {
      const ctx = await buildContext();
      await handleSqlQuery(ctx, conn, {
        sql: cmdOpts.sql,
        file: cmdOpts.file,
        schema: cmdOpts.schema,
        maxRows: parsePositiveInt(cmdOpts.maxRows, '--max-rows'),
        confirmProduction: cmdOpts.confirmProduction
      });
    });
  sqlCmd
    .command('exec <conn>')
    .description('执行写入、DDL 或其他单条 SQL 语句')
    .option('-c, --sql <sql>', 'SQL 语句')
    .option('-f, --file <file>', 'SQL 文件路径')
    .option('--schema <schema>', '默认 Schema / 数据库')
    .option('--max-rows <n>', '最大返回行数')
    .option('--confirm-production <name>', '生产连接二次确认（需与连接名完全一致）')
    .option('--allow-unbounded-write', '明确允许执行不带顶层 WHERE 的 UPDATE/DELETE')
    .action(async (conn: string, cmdOpts) => {
      const ctx = await buildContext();
      await handleSqlExec(ctx, conn, {
        sql: cmdOpts.sql,
        file: cmdOpts.file,
        schema: cmdOpts.schema,
        maxRows: parsePositiveInt(cmdOpts.maxRows, '--max-rows'),
        confirmProduction: cmdOpts.confirmProduction,
        allowUnboundedWrite: Boolean(cmdOpts.allowUnboundedWrite)
      });
    });
  sqlCmd
    .command('explain <conn>')
    .description('查看 SQL 执行计划')
    .option('-c, --sql <sql>', 'SQL 语句')
    .option('-f, --file <file>', 'SQL 文件路径')
    .option('--schema <schema>', '默认 Schema / 数据库')
    .option('--confirm-production <name>', '生产连接二次确认（需与连接名完全一致）')
    .action(async (conn: string, cmdOpts) => {
      const ctx = await buildContext();
      await handleSqlExplain(ctx, conn, {
        sql: cmdOpts.sql,
        file: cmdOpts.file,
        schema: cmdOpts.schema,
        confirmProduction: cmdOpts.confirmProduction
      });
    });

  const metaCmd = program.command('meta').description('浏览数据库元数据、表结构与 DDL');
  metaCmd
    .command('namespaces <conn>')
    .description('列出连接的命名空间 / Schema')
    .action(async (conn: string) => {
      await handleMetaNamespaces(await buildContext(), conn);
    });
  metaCmd
    .command('objects <conn>')
    .description('列出表、视图等对象')
    .option('--schema <schema>', 'Schema 名称')
    .option('--type <type>', '过滤对象类型（如 table、view）')
    .option('--keyword <keyword>', '名称关键词过滤')
    .option('--page-size <n>', '返回条数')
    .action(async (conn: string, cmdOpts) => {
      await handleMetaObjects(await buildContext(), conn, {
        schema: cmdOpts.schema,
        type: cmdOpts.type,
        keyword: cmdOpts.keyword,
        pageSize: parsePositiveInt(cmdOpts.pageSize, '--page-size')
      });
    });
  metaCmd
    .command('describe <conn> <object>')
    .description('查看表或视图的列与索引结构')
    .option('--schema <schema>', 'Schema 名称')
    .action(async (conn: string, object: string, cmdOpts) => {
      await handleMetaDescribe(await buildContext(), conn, object, { schema: cmdOpts.schema });
    });
  metaCmd
    .command('ddl <conn> <object>')
    .description('导出对象 DDL')
    .option('--schema <schema>', 'Schema 名称')
    .action(async (conn: string, object: string, cmdOpts) => {
      await handleMetaDdl(await buildContext(), conn, object, { schema: cmdOpts.schema });
    });
  metaCmd
    .command('search <conn> <keyword>')
    .description('按关键词搜索对象与列')
    .option('--schema <schema>', 'Schema 名称')
    .option('--limit <n>', '最大条数')
    .action(async (conn: string, keyword: string, cmdOpts) => {
      await handleMetaSearch(await buildContext(), conn, keyword, {
        schema: cmdOpts.schema,
        limit: parsePositiveInt(cmdOpts.limit, '--limit')
      });
    });

  program
    .command('export <conn>')
    .description('导出表或查询结果（csv | json | xlsx | sql）')
    .option('--table <table>', '导出指定表')
    .option('-c, --sql <sql>', '导出查询 SQL')
    .option('-f, --file <file>', '从文件读取查询 SQL')
    .option('--schema <schema>', 'Schema 名称')
    .option('--format <format>', '导出文件格式：csv | json | xlsx | sql', 'csv')
    .option('-o, --output <file>', '输出文件路径（不指定则输出到 stdout）')
    .option('--bom', '导出 CSV 时写入 UTF-8 BOM（便于 Excel 打开中文）')
    .option('--confirm-production <name>', '生产连接二次确认（需与连接名完全一致）')
    .action(async (conn: string, cmdOpts) => {
      const ctx = await buildContext();
      await handleExport(ctx, conn, {
        table: cmdOpts.table,
        sql: cmdOpts.sql,
        file: cmdOpts.file,
        schema: cmdOpts.schema,
        exportFormat: cmdOpts.format,
        output: cmdOpts.output,
        bom: Boolean(cmdOpts.bom),
        confirmProduction: cmdOpts.confirmProduction
      });
    });

  program
    .command('api <method> <path>')
    .description('调用当前身份有权访问的业务 API（仅接受 /api/ 相对路径）')
    .option('-f, --field <k=v>', 'JSON 请求体字段（可重复指定）', (v, acc: string[]) => [...acc, v], [])
    .option('--data <jsonOrAtFile>', '原始 JSON 字符串或 @file.json')
    .option('--confirm-production <name>', '生产连接二次确认')
    .action(async (method: string, apiPath: string, cmdOpts) => {
      const ctx = await buildContext();
      await handleApi(ctx, method, apiPath, {
        fields: cmdOpts.field,
        data: cmdOpts.data,
        confirmProduction: cmdOpts.confirmProduction
      });
    });

  try {
    await program.parseAsync(argv, { from: 'node' });
    return ExitCode.OK;
  } catch (error) {
    if (error instanceof CommanderError) {
      if (error.exitCode === 0) return ExitCode.OK;
      return ExitCode.USAGE_ERROR;
    }
    const rawFormat = program.opts<{ format?: string }>().format;
    const format: OutputFormat =
      rawFormat && VALID_FORMATS.has(rawFormat.toLowerCase() as OutputFormat)
        ? (rawFormat.toLowerCase() as OutputFormat)
        : resolveOutputFormat(undefined, isStdoutTty);
    stderr.write(formatErrorOutput(error, format) + '\n');
    return error instanceof CliError ? error.exitCode : ExitCode.EXECUTION_FAILED;
  }
}

// 仅在直接作为 CLI 脚本执行时启动（兼容 symlink 与 Windows mdd.cmd），避免 vitest 导入时触发
const isDirectMain = (() => {
  if (process.env.VITEST) return false;
  const entry = process.argv[1];
  if (!entry) return false;
  try {
    return realpathSync(entry) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return entry.endsWith('mdd.js') || entry.endsWith('/mdd') || entry.endsWith('\\mdd');
  }
})();

if (isDirectMain) {
  runCli(process.argv).then((code) => {
    process.exitCode = code;
  });
}
