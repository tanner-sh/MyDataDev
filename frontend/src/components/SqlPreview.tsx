import { Card, Typography } from 'antd';

const { Text } = Typography;

export function SqlPreview({ sql }: { sql: string[] }) {
  return (
    <Card size="small" title="变更语句预览" className="sql-preview-card">
      {sql.length === 0 ? <Text type="secondary">尚未生成预览。</Text> : <pre>{sql.join('\n')}</pre>}
    </Card>
  );
}
