import { Alert, Button, Collapse, Space, Tabs, Tag, Typography } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { useMemo } from 'react';
import { buildMcpClientGuides, type McpConfigSnippet } from '../mcpClientConfig';

const { Paragraph, Text, Title } = Typography;

type McpClientGuideTabsProps = {
  endpoint: string;
  credential?: string;
  compact?: boolean;
  onCopy: (value: string, label: string) => void | Promise<void>;
};

export function McpClientGuideTabs({ endpoint, credential, compact = false, onCopy }: McpClientGuideTabsProps) {
  const guides = useMemo(() => buildMcpClientGuides(endpoint, credential), [credential, endpoint]);

  const renderSnippet = (snippet: McpConfigSnippet) => (
    <section className={`mcp-code-card${snippet.kind ? ` is-${snippet.kind}` : ''}`} key={snippet.id}>
      <div className="mcp-code-card-header">
        <Space size={6} wrap>
          <Text strong>{snippet.title}</Text>
          {snippet.kind === 'persistent' && <Tag color="success">推荐 · 长期有效</Tag>}
          {snippet.kind === 'temporary' && <Tag color="warning">关闭终端即失效</Tag>}
          <Text type="secondary">{snippet.language}</Text>
        </Space>
        <Button
          size="small"
          type="text"
          icon={<CopyOutlined />}
          aria-label={`复制${snippet.title}`}
          onClick={() => void onCopy(snippet.content, snippet.title)}
        >
          复制
        </Button>
      </div>
      {snippet.hint && <Text type="secondary" className="mcp-code-card-hint">{snippet.hint}</Text>}
      <pre><code>{snippet.content}</code></pre>
    </section>
  );

  return (
    <Tabs
      className="mcp-client-tabs"
      defaultActiveKey="codex"
      destroyOnHidden={false}
      items={guides.map((guide) => ({
        key: guide.id,
        label: guide.label,
        children: (
          <div className="mcp-client-guide">
            <Alert
              type={guide.id === 'claude-desktop' ? 'warning' : 'info'}
              showIcon
              title={guide.summary}
            />
            <section className="mcp-config-scope-section">
              <Title level={5}>选择配置范围</Title>
              <div className="mcp-config-scope-grid">
                {guide.scopes.map((scope) => (
                  <div className="mcp-config-scope-card" key={scope.id}>
                    <Space size={[6, 6]} wrap>
                      <Tag color={scope.id === 'global' ? 'blue' : 'purple'}>
                        {scope.id === 'global' ? '全局配置' : '项目级配置'}
                      </Tag>
                      {scope.recommended && <Tag color="success">推荐</Tag>}
                      {scope.availability === 'unsupported' && <Tag>不支持</Tag>}
                      {scope.availability === 'client-dependent' && <Tag>取决于客户端</Tag>}
                    </Space>
                    <Text code>{scope.location}</Text>
                    <Paragraph type="secondary">{scope.description}</Paragraph>
                  </div>
                ))}
              </div>
            </section>
            <ul className="mcp-help-list">
              {guide.notes.map((note) => <li key={note}>{note}</li>)}
            </ul>
            {/* 先「保存 Key」（一次设置长期有效），再客户端配置；只在当前终端有效的写法收在最后，
                默认折叠并写明限制 —— 新手照着临时写法做完、换个终端就 401，还以为是 Key 错了。 */}
            {guide.snippets.some((snippet) => snippet.kind === 'persistent') && (
              <Title level={5}>第一步：保存 Key（只需一次）</Title>
            )}
            <div className="mcp-snippet-list">
              {guide.snippets.filter((snippet) => snippet.kind === 'persistent').map(renderSnippet)}
            </div>
            {guide.snippets.some((snippet) => snippet.kind === 'persistent') && (
              <Title level={5}>第二步：配置客户端</Title>
            )}
            <div className="mcp-snippet-list">
              {guide.snippets.filter((snippet) => !snippet.kind).map(renderSnippet)}
            </div>
            {guide.snippets.some((snippet) => snippet.kind === 'temporary') && (
              <Collapse
                size="small"
                className="mcp-temporary-snippets"
                items={[{
                  key: 'temporary',
                  label: '只想临时试一下？仅当前终端有效的写法',
                  children: (
                    <div className="mcp-snippet-list">
                      <Alert type="warning" showIcon title="这种写法只在当前终端窗口有效：关掉窗口、新开终端或从桌面图标启动客户端都读不到 Key，会返回 401。长期使用请用上面的「保存 Key」。" />
                      {guide.snippets.filter((snippet) => snippet.kind === 'temporary').map(renderSnippet)}
                    </div>
                  )
                }]}
              />
            )}
            {!compact && (
              <div className="mcp-client-verify">
                <Title level={5}>验证连接</Title>
                <ol className="mcp-help-list">
                  {guide.verify.map((step) => <li key={step}>{step}</li>)}
                </ol>
              </div>
            )}
            {credential && (
              <Paragraph type="secondary" className="mcp-credential-reminder">
                当前示例包含刚生成的真实凭据。请保存到个人配置或密钥管理工具，不要提交到 Git、工单或聊天记录。
              </Paragraph>
            )}
          </div>
        )
      }))}
    />
  );
}
