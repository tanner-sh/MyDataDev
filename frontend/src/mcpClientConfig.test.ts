import { describe, expect, it } from 'vitest';
import {
  buildMcpClientGuides,
  antigravityMcpConfig,
  claudeCodeJsonConfig,
  claudeDesktopConfig,
  codexStaticHeaderTomlConfig,
  codexTomlConfig,
  cursorMcpConfig,
  geminiMcpConfig,
  genericMcpConfig
} from './mcpClientConfig';

const ENDPOINT = 'https://dbadmin.example.internal/mcp';
const CREDENTIAL = 'codex-cli.secret-value';

describe('MCP client configuration generators', () => {
  it('generates native remote HTTP shapes for supported clients', () => {
    expect(codexTomlConfig(ENDPOINT)).toContain(`url = "${ENDPOINT}"`);
    expect(codexTomlConfig(ENDPOINT)).toContain('bearer_token_env_var = "MYDATADEV_MCP_TOKEN"');
    expect(codexStaticHeaderTomlConfig(ENDPOINT, CREDENTIAL)).toContain(
      `http_headers = { "Authorization" = "Bearer ${CREDENTIAL}" }`
    );
    expect(codexStaticHeaderTomlConfig(ENDPOINT, CREDENTIAL)).not.toContain('bearer_token_env_var');

    expect(JSON.parse(claudeCodeJsonConfig(ENDPOINT, CREDENTIAL))).toMatchObject({
      mcpServers: { mydatadev: { type: 'http', url: ENDPOINT, headers: { Authorization: `Bearer ${CREDENTIAL}` } } }
    });
    expect(JSON.parse(cursorMcpConfig(ENDPOINT, CREDENTIAL))).toMatchObject({
      mcpServers: { mydatadev: { url: ENDPOINT, headers: { Authorization: `Bearer ${CREDENTIAL}` } } }
    });
    expect(JSON.parse(geminiMcpConfig(ENDPOINT, CREDENTIAL))).toMatchObject({
      mcpServers: { mydatadev: { httpUrl: ENDPOINT, trust: false } }
    });
    expect(JSON.parse(genericMcpConfig(ENDPOINT, CREDENTIAL))).toMatchObject({
      mcpServers: { mydatadev: { type: 'streamable-http', url: ENDPOINT } }
    });
  });

  it.each([undefined, CREDENTIAL])('generates only Antigravity-supported remote fields with credential %s', credential => {
    const endpoint = 'https://example.internal/mcp?name="analytics"';
    const expected = {
      mcpServers: {
        mydatadev: {
          serverUrl: endpoint,
          headers: { Authorization: `Bearer ${credential || '<AGENT_API_KEY>'}` }
        }
      }
    };
    expect(JSON.parse(antigravityMcpConfig(endpoint, credential))).toEqual(expected);
    const guide = buildMcpClientGuides(endpoint, credential).find(item => item.id === 'antigravity');
    expect(guide?.label).toBe('Antigravity IDE');
    expect(JSON.parse(guide!.snippets[0].content)).toEqual(expected);
  });

  it('uses an stdio bridge for Claude Desktop and permits trusted private HTTP endpoints', () => {
    const config = JSON.parse(claudeDesktopConfig('http://127.0.0.1:8080/mcp', CREDENTIAL));
    expect(config.mcpServers.mydatadev).toMatchObject({
      command: 'npx',
      env: { AUTH_HEADER: `Bearer ${CREDENTIAL}` }
    });
    expect(config.mcpServers.mydatadev.args).toEqual(expect.arrayContaining([
      'mcp-remote',
      'Authorization:${AUTH_HEADER}',
      '--allow-http'
    ]));

    const httpsConfig = JSON.parse(claudeDesktopConfig(ENDPOINT, CREDENTIAL));
    expect(httpsConfig.mcpServers.mydatadev.args).not.toContain('--allow-http');
  });

  it('keeps secrets out of ordinary help examples and injects them only for one-time onboarding', () => {
    const help = buildMcpClientGuides(ENDPOINT);
    const helpText = help.flatMap((guide) => guide.snippets.map((snippet) => snippet.content)).join('\n');
    expect(helpText).not.toContain(CREDENTIAL);
    expect(helpText).toContain('MYDATADEV_MCP_TOKEN');
    expect(helpText).toContain('<AGENT_API_KEY>');
    expect(claudeCodeJsonConfig(ENDPOINT)).toContain('Bearer ${MYDATADEV_MCP_TOKEN}');
    expect(cursorMcpConfig(ENDPOINT)).toContain('Bearer ${env:MYDATADEV_MCP_TOKEN}');

    const onboarding = buildMcpClientGuides(ENDPOINT, CREDENTIAL);
    expect(onboarding.find((guide) => guide.id === 'generic')?.snippets[0].content).toContain(CREDENTIAL);
    expect(onboarding.find((guide) => guide.id === 'claude-desktop')?.snippets[0].content).toContain(CREDENTIAL);
    expect(
      onboarding.find((guide) => guide.id === 'codex')?.snippets
        .find((snippet) => snippet.id === 'codex-static-header-toml')?.content
    ).toContain(CREDENTIAL);

    const codexHelp = help.find((guide) => guide.id === 'codex');
    expect(
      codexHelp?.snippets.find((snippet) => snippet.id === 'codex-static-header-toml')?.content
    ).toContain('<AGENT_API_KEY>');
  });

  it('distinguishes global and project-scoped configuration for every client', () => {
    const guides = buildMcpClientGuides(ENDPOINT);
    expect(guides.every((guide) => guide.scopes.map((scope) => scope.id).join(',') === 'global,project')).toBe(true);

    // 新手首选全局：配一次所有项目都能用；项目级留给需要按项目隔离数据源的场景。
    expect(guides.find((guide) => guide.id === 'codex')?.scopes).toMatchObject([
      { id: 'global', location: '~/.codex/config.toml', recommended: true },
      { id: 'project', location: '<项目根目录>/.codex/config.toml' }
    ]);
    for (const id of ['codex', 'claude-code', 'cursor', 'gemini']) {
      const scopes = guides.find((guide) => guide.id === id)!.scopes;
      expect(scopes.find((scope) => scope.id === 'global')?.recommended).toBe(true);
      expect(scopes.find((scope) => scope.id === 'project')?.recommended).toBeFalsy();
    }
    expect(guides.find((guide) => guide.id === 'claude-desktop')?.scopes).toMatchObject([
      { id: 'global', availability: 'supported' },
      { id: 'project', availability: 'unsupported' }
    ]);
  });

  it('escapes endpoint values in generated JSON and TOML', () => {
    const endpoint = 'https://example.internal/mcp?name="analytics"';
    expect(JSON.parse(genericMcpConfig(endpoint)).mcpServers.mydatadev.url).toBe(endpoint);
    expect(codexTomlConfig(endpoint)).toContain('name=\\"analytics\\"');
  });

  it('recommends persisting the key at user level first and labels one-shot commands as temporary', () => {
    for (const credential of [undefined, CREDENTIAL]) {
      const guides = buildMcpClientGuides(ENDPOINT, credential);
      for (const id of ['codex', 'claude-code', 'cursor', 'gemini']) {
        const snippets = guides.find((guide) => guide.id === id)!.snippets;
        const persistent = snippets.filter((snippet) => snippet.kind === 'persistent');
        const temporary = snippets.filter((snippet) => snippet.kind === 'temporary');
        expect(persistent.map((snippet) => snippet.language)).toEqual(['bash', 'bash', 'powershell']);
        expect(snippets.slice(0, 3)).toEqual(persistent);
        expect(temporary).toHaveLength(3);
        expect(temporary.every((snippet) => snippet.title.includes('仅当前终端有效') && snippet.hint?.includes('关掉窗口'))).toBe(true);
        // 保存 Key 的命令一律交互输入，不把 Key 写进命令本身（会进 shell 历史）。
        expect(persistent.every((snippet) => !snippet.content.includes(CREDENTIAL))).toBe(true);
      }
    }
    const [zsh, bash, windows] = buildMcpClientGuides(ENDPOINT).find((guide) => guide.id === 'codex')!.snippets;
    expect(zsh.content).toContain('umask 077');
    expect(zsh.content).toContain('~/.zshrc');
    expect(zsh.content).toContain("printf 'export MYDATADEV_MCP_TOKEN=%q\\n'");
    expect(bash.content).toContain('~/.bashrc');
    expect(windows.content).toContain("SetEnvironmentVariable('MYDATADEV_MCP_TOKEN', $key, 'User')");
    expect(windows.content).toContain('-AsSecureString');
  });

  it('explains that desktop apps launched from the Dock do not read shell startup files', () => {
    const cursor = buildMcpClientGuides(ENDPOINT).find((guide) => guide.id === 'cursor')!;
    expect(cursor.notes.join('\n')).toContain('不会读取 ~/.zshrc');
  });
});

