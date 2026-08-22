import { memo, useMemo, useState } from 'react';
import { Alert, Space, Tag, Typography } from 'antd';
import { BulbOutlined } from '@ant-design/icons';
import { explainFindings, explainFindingsSummary, isExplainResult } from '../explainInsights';

const { Text } = Typography;

/**
 * 执行计划解读面板。
 *
 * <p>计划本身照旧完整展示 —— 这里只是把「明显有问题」的信号提到前面，并说明为什么值得关心。
 * 没有发现问题时也给一句结论：静默会让人怀疑功能有没有生效。</p>
 */
export const ExplainInsightsPanel = memo(function ExplainInsightsPanel({ columns, rows }: {
  columns: string[];
  rows: unknown[][];
}) {
  const [expanded, setExpanded] = useState(true);
  const findings = useMemo(() => explainFindings(columns, rows), [columns, rows]);
  if (!isExplainResult(columns)) return null;

  const hasWarning = findings.some((finding) => finding.level === 'warning');
  return (
    <Alert
      className="explain-insights"
      type={hasWarning ? 'warning' : findings.length > 0 ? 'info' : 'success'}
      showIcon
      icon={<BulbOutlined />}
      title={explainFindingsSummary(findings)}
      action={findings.length > 0 ? (
        <Text className="explain-insights-toggle" onClick={() => setExpanded((current) => !current)}>
          {expanded ? '收起' : '展开'}
        </Text>
      ) : undefined}
      description={findings.length > 0 && expanded ? (
        <Space orientation="vertical" size={6} className="full-width">
          {findings.map((finding) => (
            <div key={finding.code} className="explain-finding">
              <Space size={6} wrap>
                <Tag color={finding.level === 'warning' ? 'warning' : 'default'}>
                  {finding.level === 'warning' ? '关注' : '提示'}
                </Tag>
                <Text strong>{finding.title}</Text>
                {/* 行号按计划里的顺序给出，方便对照下面的表格。 */}
                <Text type="secondary">第 {finding.rows.map((index) => index + 1).join('、')} 行</Text>
              </Space>
              <Text type="secondary" className="explain-finding-detail">{finding.detail}</Text>
            </div>
          ))}
        </Space>
      ) : undefined}
    />
  );
});
