import { memo, useMemo } from 'react';
import { Alert, Table, Tag, Tooltip, Typography } from 'antd';
import { buildScriptLog, hiddenResultTabCount, summarizeScriptLog, type ScriptLogEntry } from '../scriptExecutionLog';
import type { SqlStatementResult } from '../types';

const { Text } = Typography;

/**
 * 多语句执行的日志：一行一条语句。DML、DDL 不再各占一个结果标签，查询结果与失败的那条另有标签。
 * 解析时的说明（被忽略的 SQL*Plus 指令、PROMPT 文本、按哪种方式分的段）放在最上面。
 */
export const ScriptExecutionLog = memo(function ScriptExecutionLog({ results, notices }: {
  results: SqlStatementResult[];
  notices?: string[];
}) {
  const entries = useMemo(() => buildScriptLog(results), [results]);
  const summary = useMemo(() => summarizeScriptLog(entries), [entries]);
  const hiddenTabs = hiddenResultTabCount(results);
  return (
    <div className="script-execution-log">
      <div className="script-execution-log-summary">
        <Text>共 {summary.total} 条：成功 {summary.succeeded}{summary.failed > 0 ? <Text type="danger">，失败 {summary.failed}</Text> : null}</Text>
        <Text type="secondary">查询 {summary.queries} · 影响 {summary.affectedRows} 行 · 语句耗时合计 {summary.elapsedMs}ms</Text>
        {hiddenTabs > 0 && <Text type="secondary">另有 {hiddenTabs} 个查询结果未单独成标签，可只选中那条语句再执行查看</Text>}
      </div>
      {notices && notices.length > 0 && (
        <Alert type="info" showIcon className="script-execution-log-notices"
          title={notices.length === 1 ? notices[0] : `解析说明（${notices.length}）`}
          description={notices.length > 1 ? <ul>{notices.map((notice) => <li key={notice}>{notice}</li>)}</ul> : undefined} />
      )}
      <Table<ScriptLogEntry>
        size="small"
        rowKey="index"
        className="script-execution-log-table"
        dataSource={entries}
        pagination={false}
        virtual
        scroll={{ y: 360 }}
        rowClassName={(entry) => entry.status === 'FAILED' ? 'script-log-failed' : ''}
        columns={[
          { title: '#', dataIndex: 'index', width: 56 },
          {
            title: '语句', dataIndex: 'summary', ellipsis: { showTitle: false },
            render: (summaryText: string, entry) => (
              <Tooltip title={entry.errorMessage ? `${summaryText}\n\n${entry.errorMessage}` : summaryText} placement="topLeft">
                <span className="script-log-sql">{summaryText}</span>
              </Tooltip>
            )
          },
          {
            title: '结果', key: 'outcome', width: 180,
            render: (_, entry) => entry.status === 'FAILED'
              ? <Tag color="error">失败</Tag>
              : entry.resultSet
                ? <Text type="secondary">返回 {entry.rows} 行</Text>
                : <Text type="secondary">{entry.affectedRows == null ? '已执行' : `影响 ${entry.affectedRows} 行`}</Text>
          },
          { title: '耗时', dataIndex: 'elapsedMs', width: 90, align: 'right', render: (value: number) => `${value}ms` }
        ]}
      />
    </div>
  );
});
