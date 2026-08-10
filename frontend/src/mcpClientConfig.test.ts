import { describe, expect, it } from 'vitest';
import {
  buildMcpClientGuides,
  claudeCodeJsonConfig,
  claudeDesktopConfig,
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
  });

  it('escapes endpoint values in generated JSON and TOML', () => {
    const endpoint = 'https://example.internal/mcp?name="analytics"';
    expect(JSON.parse(genericMcpConfig(endpoint)).mcpServers.mydatadev.url).toBe(endpoint);
    expect(codexTomlConfig(endpoint)).toContain('name=\\"analytics\\"');
  });
});
