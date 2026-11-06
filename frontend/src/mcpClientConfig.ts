export const MCP_SERVER_NAME = 'mydatadev';
export const MCP_TOKEN_ENV_VAR = 'MYDATADEV_MCP_TOKEN';
export const MCP_CREDENTIAL_PLACEHOLDER = '<AGENT_API_KEY>';

export type McpClientId = 'codex' | 'claude-code' | 'claude-desktop' | 'cursor' | 'gemini' | 'antigravity' | 'generic';

export type McpConfigSnippet = {
  id: string;
  title: string;
  language: 'bash' | 'powershell' | 'toml' | 'json' | 'text';
  content: string;
  /**
   * persistent：一次设置长期有效（推荐，排在最前）；temporary：只在当前终端窗口里有效，
   * 关掉就没了，界面上收进「临时试用」并写明限制。缺省是普通的客户端配置。
   */
  kind?: 'persistent' | 'temporary';
  /** 标题下面的一句说明。 */
  hint?: string;
};

/** 保存 Key 的文件：只有当前用户可读，由 shell 启动文件加载。 */
export const MCP_TOKEN_ENV_FILE = '~/.config/mydatadev/mcp.env';

function persistentPosixTokenSetup(shell: 'zsh' | 'bash') {
  const prompt = shell === 'zsh'
    ? 'read -s "MDD_KEY?请输入 Agent API Key: " && echo'
    : 'read -rsp "请输入 Agent API Key: " MDD_KEY && echo';
  const rc = shell === 'zsh' ? '~/.zshrc' : '~/.bashrc';
  return [
    '# 只需做一次：Key 存进只有自己可读的文件，之后新开的终端自动加载；轮换 Key 后重跑一遍即可',
    'mkdir -p ~/.config/mydatadev && chmod 700 ~/.config/mydatadev',
    prompt,
    `(umask 077 && printf 'export ${MCP_TOKEN_ENV_VAR}=%q\\n' "$MDD_KEY" > ${MCP_TOKEN_ENV_FILE}) && unset MDD_KEY`,
    `grep -qs 'mydatadev/mcp.env' ${rc} || echo '[ -f ${MCP_TOKEN_ENV_FILE} ] && . ${MCP_TOKEN_ENV_FILE}' >> ${rc}`,
    `. ${MCP_TOKEN_ENV_FILE}`
  ].join('\n');
}

const PERSISTENT_WINDOWS_TOKEN_SETUP = [
  '# 只需做一次：写入当前用户的环境变量，之后新开的终端和重新启动的应用都能读到；轮换 Key 后重跑一遍即可',
  '$secure = Read-Host "请输入 Agent API Key" -AsSecureString',
  '$key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))',
  `[Environment]::SetEnvironmentVariable('${MCP_TOKEN_ENV_VAR}', $key, 'User')`,
  `$env:${MCP_TOKEN_ENV_VAR} = $key`,
  'Remove-Variable key, secure'
].join('\n');

const TEMPORARY_HINT = '只在当前这个终端窗口里有效：关掉窗口、新开终端或从桌面图标启动客户端都读不到，需要重新输入。只适合临时试用。';

/**
 * 保存 Key 的几段命令。推荐的是持久化到用户级（新开终端、重启电脑都还在）；
 * 只在当前终端有效的写法保留给「先试一下」，并明确标出限制 —— 以前只给了这一种，
 * 新手照做之后换个终端就 401，还以为是 Key 错了。
 */
function tokenSetupSnippets(prefix: string): McpConfigSnippet[] {
  return [
    {
      id: `${prefix}-token-persist-zsh`, kind: 'persistent', language: 'bash',
      title: '保存 Key（macOS zsh）',
      hint: `Key 写入 ${MCP_TOKEN_ENV_FILE}（权限 600），并在 ~/.zshrc 里加载。`,
      content: persistentPosixTokenSetup('zsh')
    },
    {
      id: `${prefix}-token-persist-bash`, kind: 'persistent', language: 'bash',
      title: '保存 Key（Linux bash）',
      hint: `Key 写入 ${MCP_TOKEN_ENV_FILE}（权限 600），并在 ~/.bashrc 里加载。`,
      content: persistentPosixTokenSetup('bash')
    },
    {
      id: `${prefix}-token-persist-windows`, kind: 'persistent', language: 'powershell',
      title: '保存 Key（Windows PowerShell）',
      hint: '写入当前用户的环境变量；已经打开的终端和客户端需要重开一次才能读到。',
      content: PERSISTENT_WINDOWS_TOKEN_SETUP
    },
    {
      id: `${prefix}-token-zsh`, kind: 'temporary', language: 'bash',
      title: '仅当前终端有效（macOS zsh）', hint: TEMPORARY_HINT,
      content: 'read -s "MYDATADEV_MCP_TOKEN?请输入 Agent API Key: "\nexport MYDATADEV_MCP_TOKEN\necho'
    },
    {
      id: `${prefix}-token-bash`, kind: 'temporary', language: 'bash',
      title: '仅当前终端有效（Linux bash）', hint: TEMPORARY_HINT,
      content: 'read -rsp "请输入 Agent API Key: " MYDATADEV_MCP_TOKEN\nexport MYDATADEV_MCP_TOKEN\nprintf \'\\n\''
    },
    {
      id: `${prefix}-token-windows`, kind: 'temporary', language: 'powershell',
      title: '仅当前终端有效（Windows PowerShell 7）', hint: TEMPORARY_HINT,
      content: '$env:MYDATADEV_MCP_TOKEN = Read-Host "请输入 Agent API Key" -MaskInput'
    }
  ];
}

/** 从 Dock / 开始菜单启动的桌面客户端读不到 shell 启动文件里的变量。 */
const DESKTOP_ENV_NOTE = 'macOS 上从 Dock 或启动台打开的桌面应用不会读取 ~/.zshrc：要么在终端里用客户端自带的命令行启动（如 cursor .），要么把一次性弹窗生成的、含真实 Key 的配置保存到用户级全局配置文件（仅本机、权限 600）。Windows 写入用户环境变量后重启客户端即可。';

export type McpConfigScope = {
  id: 'global' | 'project';
  availability: 'supported' | 'unsupported' | 'client-dependent';
  location: string;
  description: string;
  recommended?: boolean;
};

export type McpClientGuide = {
  id: McpClientId;
  label: string;
  summary: string;
  scopes: McpConfigScope[];
  notes: string[];
  snippets: McpConfigSnippet[];
  verify: string[];
};

function json(value: unknown) {
  return JSON.stringify(value, null, 2);
}

function tomlString(value: string) {
  return JSON.stringify(value);
}

function posixQuote(value: string) {
  return `'${value.split("'").join(`'"'"'`)}'`;
}

function powershellQuote(value: string) {
  return `'${value.split("'").join("''")}'`;
}

function credentialOrPlaceholder(credential?: string) {
  return credential || MCP_CREDENTIAL_PLACEHOLDER;
}

export function genericMcpConfig(endpoint: string, credential?: string) {
  return json({
    mcpServers: {
      [MCP_SERVER_NAME]: {
        type: 'streamable-http',
        url: endpoint,
        headers: { Authorization: `Bearer ${credentialOrPlaceholder(credential)}` }
      }
    }
  });
}

export function codexTomlConfig(endpoint: string) {
  return [
    `[mcp_servers.${MCP_SERVER_NAME}]`,
    `url = ${tomlString(endpoint)}`,
    `bearer_token_env_var = ${tomlString(MCP_TOKEN_ENV_VAR)}`,
    'enabled = true'
  ].join('\n');
}

export function codexStaticHeaderTomlConfig(endpoint: string, credential?: string) {
  return [
    `[mcp_servers.${MCP_SERVER_NAME}]`,
    `url = ${tomlString(endpoint)}`,
    `http_headers = { "Authorization" = ${tomlString(`Bearer ${credentialOrPlaceholder(credential)}`)} }`,
    'enabled = true'
  ].join('\n');
}

export function claudeCodeJsonConfig(endpoint: string, credential?: string) {
  const token = credential || `\${${MCP_TOKEN_ENV_VAR}}`;
  return json({
    mcpServers: {
      [MCP_SERVER_NAME]: {
        type: 'http',
        url: endpoint,
        headers: { Authorization: `Bearer ${token}` }
      }
    }
  });
}

export function claudeDesktopConfig(endpoint: string, credential?: string) {
  const args = [
    '-y',
    'mcp-remote',
    endpoint,
    '--header',
    'Authorization:${AUTH_HEADER}'
  ];
  if (endpoint.toLowerCase().startsWith('http://')) args.push('--allow-http');
  return json({
    mcpServers: {
      [MCP_SERVER_NAME]: {
        command: 'npx',
        args,
        env: { AUTH_HEADER: `Bearer ${credentialOrPlaceholder(credential)}` }
      }
    }
  });
}

export function cursorMcpConfig(endpoint: string, credential?: string) {
  const token = credential || `\${env:${MCP_TOKEN_ENV_VAR}}`;
  return json({
    mcpServers: {
      [MCP_SERVER_NAME]: {
        url: endpoint,
        headers: { Authorization: `Bearer ${token}` }
      }
    }
  });
}

export function geminiMcpConfig(endpoint: string, credential?: string) {
  return json({
    mcpServers: {
      [MCP_SERVER_NAME]: {
        httpUrl: endpoint,
        headers: { Authorization: `Bearer ${credentialOrPlaceholder(credential)}` },
        trust: false
      }
    }
  });
}

/** Antigravity remote configuration: https://antigravity.google/docs/mcp */
export function antigravityMcpConfig(endpoint: string, credential?: string) {
  return json({
    mcpServers: {
      [MCP_SERVER_NAME]: {
        serverUrl: endpoint,
        headers: { Authorization: `Bearer ${credentialOrPlaceholder(credential)}` }
      }
    }
  });
}

export function buildMcpClientGuides(endpoint: string, credential?: string): McpClientGuide[] {
  return [
    {
      id: 'codex',
      label: 'Codex CLI',
      summary: '通过 Streamable HTTP 直连。推荐先把 Key 保存为用户环境变量（一次设置长期有效），再用 codex mcp add 注册到全局配置。',
      scopes: [
        {
          id: 'global',
          availability: 'supported',
          location: '~/.codex/config.toml',
          description: 'codex mcp add 默认写入用户配置，所有项目都能用，配一次即可。新手首选。',
          recommended: true
        },
        {
          id: 'project',
          availability: 'supported',
          location: '<项目根目录>/.codex/config.toml',
          description: '仅可信项目加载。需要按项目隔离数据源时使用：每个项目创建独立 MyDataDev Agent，配独立环境变量或静态 Header。'
        }
      ],
      notes: [
        '先运行下方「第一步：保存 Key」中对应系统的命令（只需一次），再运行「全局配置：使用 CLI 注册」。环境变量方案不会把 Key 写进 config.toml。',
        'bearer_token_env_var 必须填写环境变量名，不能直接填写 Agent API Key。',
        '静态 http_headers 无需设置环境变量，但会把完整 Key 明文写入配置文件；只用于本机私有配置，并确保文件不进入 Git。'
      ],
      snippets: [
        ...tokenSetupSnippets('codex'),
        {
          id: 'codex-add',
          title: '全局配置：使用 CLI 注册',
          language: 'bash',
          content: `codex mcp add ${MCP_SERVER_NAME} --url ${posixQuote(endpoint)} --bearer-token-env-var ${MCP_TOKEN_ENV_VAR}`
        },
        { id: 'codex-toml', title: '推荐：环境变量 config.toml', language: 'toml', content: codexTomlConfig(endpoint) },
        {
          id: 'codex-static-header-toml',
          title: '免环境变量：静态 http_headers（明文 Key）',
          language: 'toml',
          content: codexStaticHeaderTomlConfig(endpoint, credential)
        }
      ],
      verify: ['运行 codex mcp list 检查状态。', '启动 Codex 后输入 /mcp，再让它“使用 mydatadev 列出可用数据库连接”。']
    },
    {
      id: 'claude-code',
      label: 'Claude Code',
      summary: 'Claude Code 可直接连接 HTTP MCP。推荐先保存 Key 为用户环境变量，再用 --scope user 注册到全局配置。',
      scopes: [
        {
          id: 'global',
          availability: 'supported',
          location: '用户级配置（--scope user）',
          description: '当前用户的所有项目都能使用，配一次即可；下方带 --scope user 的 CLI 命令属于全局配置。新手首选。',
          recommended: true
        },
        {
          id: 'project',
          availability: 'supported',
          location: '<项目根目录>/.mcp.json',
          description: '随项目共享的配置。需要不同连接白名单时，为项目使用独立 MyDataDev Agent 和环境变量。'
        }
      ],
      notes: [
        '先运行「第一步：保存 Key」中对应系统的命令（只需一次），再运行「全局配置：CLI」命令。',
        '只想在某个项目里用：把 .mcp.json 放到项目根目录，首次使用时 Claude Code 会要求确认信任；JSON 里引用的是环境变量，不含真实 Key。'
      ],
      snippets: [
        ...tokenSetupSnippets('claude'),
        { id: 'claude-json', title: '项目级配置：.mcp.json', language: 'json', content: claudeCodeJsonConfig(endpoint, credential) },
        {
          id: 'claude-add-posix',
          title: '全局配置：CLI（macOS / Linux）',
          language: 'bash',
          content: `claude mcp add --transport http --scope user --header "Authorization: Bearer $${MCP_TOKEN_ENV_VAR}" ${MCP_SERVER_NAME} ${posixQuote(endpoint)}`
        },
        {
          id: 'claude-add-windows',
          title: '全局配置：CLI（Windows PowerShell）',
          language: 'powershell',
          content: `claude mcp add --transport http --scope user --header "Authorization: Bearer $env:${MCP_TOKEN_ENV_VAR}" ${MCP_SERVER_NAME} ${powershellQuote(endpoint)}`
        }
      ],
      verify: ['运行 claude mcp list 检查状态。', '在 Claude Code 中输入 /mcp，确认 mydatadev 已连接。']
    },
    {
      id: 'claude-desktop',
      label: 'Claude Desktop',
      summary: 'Claude Desktop 对私网静态 Bearer Server 使用本地 stdio 桥接；远程自定义连接器通常要求公网可达和 OAuth。',
      scopes: [
        {
          id: 'global',
          availability: 'supported',
          location: 'claude_desktop_config.json',
          description: '应用级全局配置，Claude Desktop 中的所有对话都能看到已配置的 MCP。'
        },
        {
          id: 'project',
          availability: 'unsupported',
          location: '不支持项目级配置',
          description: '不能根据代码项目自动切换。若配置多个项目 Agent，它们仍会作为全局 MCP Server 出现在 Desktop 中。'
        }
      ],
      notes: [
        '需要本机已安装 Node.js 18+，配置会通过 npx 启动第三方 mcp-remote 桥接。',
        'macOS 配置文件为 ~/Library/Application Support/Claude/claude_desktop_config.json；Windows 为 %APPDATA%\\Claude\\claude_desktop_config.json。',
        'HTTP 私网地址会自动加入 --allow-http；跨主机仍优先使用 HTTPS。'
      ],
      snippets: [
        { id: 'claude-desktop-json', title: '全局配置：claude_desktop_config.json', language: 'json', content: claudeDesktopConfig(endpoint, credential) }
      ],
      verify: ['完全退出并重新启动 Claude Desktop。', '在工具列表中确认 mydatadev 工具已出现；若失败，先检查 Node.js、npx和网络连通性。']
    },
    {
      id: 'cursor',
      label: 'Cursor',
      summary: 'Cursor 可从项目级或全局 mcp.json 直接连接远程 Streamable HTTP Server。',
      scopes: [
        {
          id: 'global',
          availability: 'supported',
          location: '~/.cursor/mcp.json',
          description: '当前用户打开的所有 Cursor 项目都能使用该 MCP，配一次即可。新手首选。',
          recommended: true
        },
        {
          id: 'project',
          availability: 'supported',
          location: '<项目根目录>/.cursor/mcp.json',
          description: '仅当前项目加载；需要按项目限制数据源时，建议配合独立 MyDataDev Agent 使用。'
        }
      ],
      notes: [
        '项目配置路径为 .cursor/mcp.json；全局路径为 ~/.cursor/mcp.json。',
        credential
          ? '当前配置已写入一次性 Key，请确保文件不进入 Git。'
          : `示例使用 \${env:${MCP_TOKEN_ENV_VAR}}，需要先保存 Key 为用户环境变量。${DESKTOP_ENV_NOTE}`
      ],
      snippets: [
        ...tokenSetupSnippets('cursor'),
        { id: 'cursor-json', title: '全局 / 项目级：mcp.json 内容', language: 'json', content: cursorMcpConfig(endpoint, credential) }
      ],
      verify: ['重启 Cursor，在 Settings > MCP 中启用并确认 mydatadev。', '在 Agent 中让它列出可用数据库连接。']
    },
    {
      id: 'gemini',
      label: 'Gemini CLI',
      summary: 'Gemini CLI 支持 HTTP transport、认证 Header和用户/项目级 settings.json。',
      scopes: [
        {
          id: 'global',
          availability: 'supported',
          location: '~/.gemini/settings.json（--scope user）',
          description: '当前用户的所有项目都能使用，配一次即可；下方 CLI 添加命令属于全局配置。新手首选。',
          recommended: true
        },
        {
          id: 'project',
          availability: 'supported',
          location: '<项目根目录>/.gemini/settings.json',
          description: '仅当前项目加载；复制 JSON 到项目配置时不要把真实 Key 提交到 Git。'
        }
      ],
      notes: [
        '先运行「第一步：保存 Key」中对应系统的命令（只需一次），再运行「全局配置：CLI」命令。',
        'CLI 添加命令会把展开后的 Header 写入配置；不要把包含真实 Key 的项目配置提交到 Git。',
        '用户配置位于 ~/.gemini/settings.json，项目配置位于 .gemini/settings.json。'
      ],
      snippets: [
        ...tokenSetupSnippets('gemini'),
        {
          id: 'gemini-add-posix',
          title: '全局配置：CLI（macOS / Linux）',
          language: 'bash',
          content: `gemini mcp add --scope user --transport http --header "Authorization: Bearer $${MCP_TOKEN_ENV_VAR}" ${MCP_SERVER_NAME} ${posixQuote(endpoint)}`
        },
        {
          id: 'gemini-add-windows',
          title: '全局配置：CLI（Windows PowerShell）',
          language: 'powershell',
          content: `gemini mcp add --scope user --transport http --header "Authorization: Bearer $env:${MCP_TOKEN_ENV_VAR}" ${MCP_SERVER_NAME} ${powershellQuote(endpoint)}`
        },
        { id: 'gemini-json', title: '全局 / 项目级：settings.json 内容', language: 'json', content: geminiMcpConfig(endpoint, credential) }
      ],
      verify: ['运行 gemini mcp list 检查连接。', '进入 Gemini CLI 后运行 /mcp list，确认工具已经发现。']
    },
    {
      id: 'antigravity',
      label: 'Antigravity IDE',
      summary: 'Antigravity 使用 serverUrl 连接远程 MCP；请复制本页专用配置。',
      scopes: [
        {
          id: 'global',
          availability: 'supported',
          location: 'MCP Servers > Manage MCP Servers > View raw config',
          description: '在 IDE 中打开实际使用的 mcp_config.json；当前文档的全局路径为 ~/.gemini/config/mcp_config.json，其他版本请以 IDE 打开的文件为准。',
          recommended: true
        },
        {
          id: 'project',
          availability: 'client-dependent',
          location: '<项目根目录>/.agents/mcp_config.json',
          description: '当前文档支持工作区配置；请确认所用版本支持此路径。包含真实 Key 的文件不要提交到 Git。'
        }
      ],
      notes: [
        '将 mydatadev 条目合并到现有 mcpServers 中，保留其他服务配置。',
        'Antigravity 使用 serverUrl 和 headers，不使用 Gemini CLI 的 httpUrl、trust，也不需要 type 字段。',
        credential
          ? '当前配置已包含刚生成的 Agent API Key，请保存到个人配置。'
          : '将 <AGENT_API_KEY> 替换为 MyDataDev 创建 Agent 时生成的 API Key。'
      ],
      snippets: [
        { id: 'antigravity-json', title: 'Antigravity：mcp_config.json 内容', language: 'json', content: antigravityMcpConfig(endpoint, credential) }
      ],
      verify: ['保存配置后，在 MCP Servers 中重新加载，确认 mydatadev 已连接。', '让 Agent 使用 mydatadev 列出可用数据库连接。']
    },
    {
      id: 'generic',
      label: '通用 JSON',
      summary: '适用于支持 mcpServers、Streamable HTTP URL和自定义 Header 的其他 AI Agent。',
      scopes: [
        {
          id: 'global',
          availability: 'client-dependent',
          location: '客户端用户配置目录',
          description: '配置路径和是否支持全局范围由具体客户端决定。'
        },
        {
          id: 'project',
          availability: 'client-dependent',
          location: '客户端项目配置目录',
          description: '优先查阅客户端文档；若支持项目配置，建议配合项目专属 MyDataDev Agent。',
          recommended: true
        }
      ],
      notes: [
        '不同客户端可能把 type 命名为 http 或 streamable-http，也可能省略 type。',
        '客户端必须在初始化和后续 session 请求中持续发送同一 Bearer Token。'
      ],
      snippets: [
        { id: 'generic-json', title: '通用客户端配置', language: 'json', content: genericMcpConfig(endpoint, credential) }
      ],
      verify: ['重新加载客户端后确认能列出 8 个 MyDataDev 查询工具。', '先调用 db_list_connections 验证凭据和连接白名单。']
    }
  ];
}
