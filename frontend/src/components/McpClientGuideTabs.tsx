import { Alert, Button, Space, Tabs, Typography } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { useMemo } from 'react';
import { buildMcpClientGuides } from '../mcpClientConfig';

const { Paragraph, Text, Title } = Typography;

type McpClientGuideTabsProps = {
  endpoint: string;
  credential?: string;
  compact?: boolean;
  onCopy: (value: string, label: string) => void | Promise<void>;
};

export function McpClientGuideTabs({ endpoint, credential, compact = false, onCopy }: McpClientGuideTabsProps) {
  const guides = useMemo(() => buildMcpClientGuides(endpoint, credential), [credential, endpoint]);

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
            <ul className="mcp-help-list">
              {guide.notes.map((note) => <li key={note}>{note}</li>)}
            </ul>
            <div className="mcp-snippet-list">
              {guide.snippets.map((snippet) => (
                <section className="mcp-code-card" key={snippet.id}>
                  <div className="mcp-code-card-header">
                    <Space size={6}>
                      <Text strong>{snippet.title}</Text>
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
                  <pre><code>{snippet.content}</code></pre>
                </section>
              ))}
            </div>
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
