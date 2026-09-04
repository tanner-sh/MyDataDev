import { memo, useMemo, useState } from 'react';
import { Alert, Button, Space, Tag, Typography } from 'antd';
import { BulbOutlined } from '@ant-design/icons';
import { explainFindings, explainFindingsSummary, isExplainResult, type ExplainFinding } from '../explainInsights';

const { Text } = Typography;

/**
 * 执行计划解读面板。
 *
 * <p>计划本身照旧完整展示 —— 这里只是把「明显有问题」的信号提到前面，并说明为什么值得关心。
 * 没有发现问题时也给一句结论：静默会让人怀疑功能有没有生效。</p>
 */
export const ExplainInsightsPanel = memo(function ExplainInsightsPanel({ columns, rows, onAskAi }: {
  columns: string[];
  rows: unknown[][];
  /**
   * 交给 AI 进一步解读。
   *
   * AI 不可用时不传，按钮就不出现。规则结论在前、AI 解读在后：规则说的是确定的事实，
   * 模型说的是可能的原因与建议，两者不该混在一起显示。
   */
  onAskAi?: (findings: ExplainFinding[]) => void;
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
      action={findings.length > 0 || onAskAi ? (
        <Space size={8}>
          {onAskAi && (
            <Button size="small" icon={<BulbOutlined />} onClick={() => onAskAi(findings)}>AI 解读</Button>
          )}
          {findings.length > 0 && (
            <Text className="explain-insights-toggle" onClick={() => setExpanded((current) => !current)}>
              {expanded ? '收起' : '展开'}
            </Text>
          )}
        </Space>
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
