import { Alert, Button, Card, Collapse, Descriptions, Space, Steps, Table, Tag, Typography } from 'antd';
import { CopyOutlined, SettingOutlined } from '@ant-design/icons';
import type { McpAgent } from '../types';
import { McpClientGuideTabs } from './McpClientGuideTabs';

const { Paragraph, Text, Title } = Typography;

type McpHelpPanelProps = {
  endpoint: string;
  enabled: boolean;
  agents: McpAgent[];
  onOpenConfig: () => void;
  onCopy: (value: string, label: string) => void | Promise<void>;
};

const tools = [
  { name: 'db_list_connections', capability: '列出白名单内的连接及各自的访问档位', level: '只读' },
  { name: 'db_list_namespaces', capability: '分页列出 Catalog 或 Schema', level: '只读' },
  { name: 'db_search_objects', capability: '搜索表和视图', level: '只读' },
  { name: 'db_describe_object', capability: '查看列、主键、索引和外键', level: '只读' },
  { name: 'db_get_object_ddl', capability: '获取表或视图 DDL', level: '只读' },
  { name: 'db_browse_table', capability: '使用游标分页浏览表数据', level: '只读' },
  { name: 'db_query', capability: '执行一条查询语句', level: '只读' },
  { name: 'db_explain', capability: '查看查询执行计划', level: '只读' },
  { name: 'db_execute', capability: '执行一条写语句（INSERT/UPDATE/DELETE，FULL 档位还可 DDL）', level: '数据读写' }
];

export function McpHelpPanel({ endpoint, enabled, agents, onOpenConfig, onCopy }: McpHelpPanelProps) {
  const activeAgents = agents.filter((agent) => agent.enabled).length;
  return (
    <div className="mcp-help-panel">
      <Card
        title="当前服务"
        extra={<Button icon={<SettingOutlined />} onClick={onOpenConfig}>返回服务配置</Button>}
      >
        {!enabled && (
          <Alert
            type="error"
            showIcon
            title="MCP Server 当前已关闭，客户端请求会返回 503。请先返回服务配置开启服务。"
            className="mcp-section-alert"
          />
        )}
        {enabled && activeAgents === 0 && (
          <Alert
            type="warning"
            showIcon
            title="MCP 已开启，但没有已启用的 Agent；请先创建 Agent 并保存一次性 API Key。"
            className="mcp-section-alert"
          />
        )}
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label="服务状态">
            <Tag color={enabled ? 'success' : 'default'}>{enabled ? '运行中' : '已关闭'}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="可用 Agent">
            <Text>{activeAgents} / {agents.length}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="MCP URL">
            <Space wrap>
              <Text code>{endpoint}</Text>
              <Button size="small" icon={<CopyOutlined />} onClick={() => void onCopy(endpoint, 'MCP URL')}>复制</Button>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="认证格式">
            <Text code>Authorization: Bearer agent-id.secret</Text>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="五步完成接入">
        <Steps
          responsive
          items={[
            { title: '配置连接', content: '在连接管理中准备数据库连接' },
            { title: '创建 Agent', content: '选择连接白名单和生产权限' },
            { title: '保存 Key', content: '完整凭据只显示一次' },
            { title: '配置客户端', content: '选择下方对应的 AI Agent' },
            { title: '验证工具', content: '先调用 db_list_connections' }
          ]}
        />
      </Card>

      <Card title="AI Agent 接入配置">
        <Alert
          type="warning"
          showIcon
          title="帮助页使用占位 Key。创建或轮换 Agent 后，一次性密钥弹窗会生成包含真实凭据的可复制配置。"
          className="mcp-section-alert"
        />
        <Alert
          type="info"
          showIcon
          title="先选择配置范围：全局配置会在所有项目中加载 MCP，项目级配置只在当前项目中加载。"
          description="配置范围不会改变数据库权限；真正可访问的数据源由 MyDataDev Agent 的连接白名单决定。需要项目隔离时，应为每个项目创建独立 Agent、API Key 和环境变量。Web 示例统一使用 MYDATADEV_MCP_TOKEN，复制到项目后可改成项目专属名称。"
          className="mcp-section-alert"
        />
        <McpClientGuideTabs endpoint={endpoint} onCopy={onCopy} />
      </Card>

      <Card title="能力与安全边界">
        <div className="mcp-help-security-grid">
          <div>
            <Title level={5}>访问控制</Title>
            <ul className="mcp-help-list">
              <li>每个 AI Agent 使用独立 API Key，只能访问自己的连接白名单。</li>
              <li>访问档位按<strong>连接</strong>授予：同一个 Agent 可以在开发库上有写权限、在生产库上只读。</li>
              <li>默认是「只读」。写权限必须在 Agent 编辑弹窗里逐条连接显式授予。</li>
              <li>生产连接必须在 Agent 上单独开启生产环境权限。</li>
              <li>跨主机访问应使用 HTTPS；CLI 通常不携带 Origin，无需配置浏览器 Origin。</li>
            </ul>
          </div>
          <div>
            <Title level={5}>SQL 防护</Title>
            <ul className="mcp-help-list">
              <li>db_query 只接受分类为查询的一条 SQL，拒绝 DML、DDL、调用、锁、会话修改、多语句和已知副作用查询；执行时设置 JDBC 只读提示、关闭自动提交并在结束时回滚。</li>
              <li>写操作必须走 db_execute，两条路径不共用。同样只接受一条语句。</li>
              <li>无论档位多高：只读连接拒绝一切写入；生产连接上的每条写语句都要求回传连接名；不含 WHERE 的 UPDATE/DELETE 需要单独确认。</li>
              <li>每次调用都写审计，写语句还会额外落 SQL 历史。</li>
              <li>数据库返回内容属于不可信输入，AI Agent 不应把数据当作指令执行。</li>
            </ul>
          </div>
        </div>
        <Table
          className="mcp-tool-table"
          rowKey="name"
          size="small"
          pagination={false}
          dataSource={tools}
          columns={[
            { title: '工具', dataIndex: 'name', render: (name: string) => <Text code>{name}</Text> },
            {
              title: '所需档位',
              dataIndex: 'level',
              width: 110,
              render: (level: string) => <Tag color={level === '只读' ? undefined : 'orange'}>{level}</Tag>
            },
            { title: '能力', dataIndex: 'capability' }
          ]}
          scroll={{ x: 620 }}
        />
      </Card>

      <Card title="常见问题">
        <Collapse
          items={[
            {
              key: '401',
              label: '401 Unauthorized',
              children: <Paragraph>检查完整凭据、Agent 是否启用以及 Key 是否已经轮换。环境变量必须传给实际启动 AI Agent 的进程。</Paragraph>
            },
            {
              key: '503',
              label: '503 Service Unavailable',
              children: <Paragraph>MyDataDev 的 MCP Server 开关已关闭，返回服务配置重新开启即可，无需重启后端。</Paragraph>
            },
            {
              key: 'network',
              label: '客户端无法连接',
              children: <Paragraph>确认地址以 /mcp 结尾。客户端与后端不在同一台机器时不要使用 127.0.0.1；远程部署还需转发 Authorization 和 MCP session Header。</Paragraph>
            },
            {
              key: 'connections',
              label: '看不到连接或生产库',
              children: <Paragraph>连接必须位于当前 Agent 的白名单中；生产连接还要求 Agent 开启生产环境权限。</Paragraph>
            },
            {
              key: 'write',
              label: '写入 SQL 被拒绝',
              children: <Paragraph>这是预期行为。即使目标连接标记为可写，MCP 当前也只实现查询、元数据浏览和执行计划能力。</Paragraph>
            }
          ]}
        />
      </Card>
    </div>
  );
}
