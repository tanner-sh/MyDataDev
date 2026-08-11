export const MCP_SERVER_NAME = 'mydatadev';
export const MCP_TOKEN_ENV_VAR = 'MYDATADEV_MCP_TOKEN';
export const MCP_CREDENTIAL_PLACEHOLDER = '<AGENT_API_KEY>';

export type McpClientId = 'codex' | 'claude-code' | 'claude-desktop' | 'cursor' | 'gemini' | 'generic';

export type McpConfigSnippet = {
  id: string;
  title: string;
  language: 'bash' | 'powershell' | 'toml' | 'json' | 'text';
  content: string;
};

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

export function buildMcpClientGuides(endpoint: string, credential?: string): McpClientGuide[] {
  const zshTokenSetup = 'read -s "MYDATADEV_MCP_TOKEN?请输入 Agent API Key: "\nexport MYDATADEV_MCP_TOKEN\necho';
  const bashTokenSetup = 'read -rsp "请输入 Agent API Key: " MYDATADEV_MCP_TOKEN\nexport MYDATADEV_MCP_TOKEN\nprintf \'\\n\'';
  const powershellTokenSetup = '$env:MYDATADEV_MCP_TOKEN = Read-Host "请输入 Agent API Key" -MaskInput';
  return [
    {
      id: 'codex',
      label: 'Codex CLI',
      summary: '通过 Streamable HTTP 直连，Codex 从环境变量读取 Bearer Token，不把密钥写进 config.toml。',
      scopes: [
        {
          id: 'global',
          availability: 'supported',
          location: '~/.codex/config.toml',
          description: 'codex mcp add 默认写入用户配置，所有项目都能看到该 MCP，并使用同一个 Agent Key 和连接白名单。'
        },
        {
          id: 'project',
          availability: 'supported',
          location: '<项目根目录>/.codex/config.toml',
          description: '仅可信项目加载。需要按项目隔离数据源时，建议每个项目创建独立 MyDataDev Agent，并使用独立环境变量。',
          recommended: true
        }
      ],
      notes: [
        '先在启动 Codex 的同一个终端中设置环境变量。',
        'bearer_token_env_var 必须填写环境变量名，不能直接填写 Agent API Key。'
      ],
      snippets: [
        { id: 'codex-token-zsh', title: 'macOS zsh：安全输入 Key', language: 'bash', content: zshTokenSetup },
        { id: 'codex-token-bash', title: 'Linux bash：安全输入 Key', language: 'bash', content: bashTokenSetup },
        { id: 'codex-token-windows', title: 'Windows PowerShell 7：安全输入 Key', language: 'powershell', content: powershellTokenSetup },
        {
          id: 'codex-add',
          title: '全局配置：使用 CLI 注册',
          language: 'bash',
          content: `codex mcp add ${MCP_SERVER_NAME} --url ${posixQuote(endpoint)} --bearer-token-env-var ${MCP_TOKEN_ENV_VAR}`
        },
        { id: 'codex-toml', title: '全局 / 项目级：config.toml 内容', language: 'toml', content: codexTomlConfig(endpoint) }
      ],
      verify: ['运行 codex mcp list 检查状态。', '启动 Codex 后输入 /mcp，再让它“使用 mydatadev 列出可用数据库连接”。']
    },
    {
      id: 'claude-code',
      label: 'Claude Code',
      summary: 'Claude Code 可直接连接 HTTP MCP，并支持在共享 JSON 中用环境变量注入认证 Header。',
      scopes: [
        {
          id: 'global',
          availability: 'supported',
          location: '用户级配置（--scope user）',
          description: '当前用户的所有项目都能使用；下方带 --scope user 的 CLI 命令属于全局配置。'
        },
        {
          id: 'project',
          availability: 'supported',
          location: '<项目根目录>/.mcp.json',
          description: '随项目共享的配置。需要不同连接白名单时，为项目使用独立 MyDataDev Agent 和环境变量。',
          recommended: true
        }
      ],
      notes: [
        '推荐把下面 JSON 保存为项目根目录 .mcp.json；首次使用项目配置时 Claude Code 会要求确认信任。',
        '如需跨项目使用，可用 --scope user 添加到用户配置。'
      ],
      snippets: [
        { id: 'claude-token-zsh', title: 'macOS zsh：安全输入 Key', language: 'bash', content: zshTokenSetup },
        { id: 'claude-token-bash', title: 'Linux bash：安全输入 Key', language: 'bash', content: bashTokenSetup },
        { id: 'claude-token-windows', title: 'Windows PowerShell 7：安全输入 Key', language: 'powershell', content: powershellTokenSetup },
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
          description: '当前用户打开的所有 Cursor 项目都能使用该 MCP。'
        },
        {
          id: 'project',
          availability: 'supported',
          location: '<项目根目录>/.cursor/mcp.json',
          description: '仅当前项目加载；需要按项目限制数据源时，建议配合独立 MyDataDev Agent 使用。',
          recommended: true
        }
      ],
      notes: [
        '项目配置路径为 .cursor/mcp.json；全局路径为 ~/.cursor/mcp.json。',
        credential
          ? '当前配置已写入一次性 Key，请确保文件不进入 Git。'
          : `示例使用 \${env:${MCP_TOKEN_ENV_VAR}}；从桌面图标启动 Cursor 时也要确保该环境变量对桌面进程可见。`
      ],
      snippets: [
        { id: 'cursor-token-zsh', title: 'macOS zsh：安全输入 Key', language: 'bash', content: zshTokenSetup },
        { id: 'cursor-token-bash', title: 'Linux bash：安全输入 Key', language: 'bash', content: bashTokenSetup },
        { id: 'cursor-token-windows', title: 'Windows PowerShell 7：安全输入 Key', language: 'powershell', content: powershellTokenSetup },
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
          description: '当前用户的所有项目都能使用；下方 CLI 添加命令属于全局配置。'
        },
        {
          id: 'project',
          availability: 'supported',
          location: '<项目根目录>/.gemini/settings.json',
          description: '仅当前项目加载；复制 JSON 到项目配置时不要把真实 Key 提交到 Git。',
          recommended: true
        }
      ],
      notes: [
        'CLI 添加命令会把展开后的 Header 写入配置；不要把包含真实 Key 的项目配置提交到 Git。',
        '用户配置位于 ~/.gemini/settings.json，项目配置位于 .gemini/settings.json。'
      ],
      snippets: [
        { id: 'gemini-token-zsh', title: 'macOS zsh：安全输入 Key', language: 'bash', content: zshTokenSetup },
        { id: 'gemini-token-bash', title: 'Linux bash：安全输入 Key', language: 'bash', content: bashTokenSetup },
        { id: 'gemini-token-windows', title: 'Windows PowerShell 7：安全输入 Key', language: 'powershell', content: powershellTokenSetup },
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
