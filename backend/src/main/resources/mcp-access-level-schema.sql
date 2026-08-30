-- MCP Agent 的连接授权从「白名单」升级为「白名单 + 访问档位」。
-- 既有授权一律落到 READ_ONLY，与升级前的行为完全一致：分档是加法，不改变任何已有 Agent 的能力。
ALTER TABLE mcp_agent_connection ADD COLUMN IF NOT EXISTS access_level VARCHAR(20) NOT NULL DEFAULT 'READ_ONLY';
