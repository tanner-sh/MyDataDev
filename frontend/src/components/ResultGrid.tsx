import { useEffect, useState } from 'react';
import { Empty, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ResultRow, SqlResult } from '../types';

export function ResultGrid({ result, fill = false }: { result: SqlResult | null; fill?: boolean }) {
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  const emptyClassName = fill ? 'empty-state empty-state-fill' : 'empty-state';

  useEffect(() => {
    setCurrentPage(1);
  }, [result]);

  if (!result) return <Empty className={emptyClassName} description="执行查询后查看结果。" />;
  if (!result.resultSet) return <Empty className={emptyClassName} description={`影响 ${result.affectedRows} 行。`} />;
  const columns: ColumnsType<ResultRow> = [
    {
      title: '序号',
      key: '__index',
      width: 70,
      fixed: 'left',
      render: (_value, _row, index) => (currentPage - 1) * pageSize + index + 1
    },
    ...result.columns.map((column) => ({
      title: column,
      dataIndex: column,
      key: column,
      ellipsis: true,
      render: (value: unknown) => String(value ?? '')
    }))
  ];
  const rows = result.rows.map((row, index) => ({ key: String(index), ...row }));
  return (
    <Table<ResultRow>
      size="small"
      className={fill ? 'data-grid data-grid-fill' : 'data-grid'}
      columns={columns}
      dataSource={rows}
      pagination={{
        current: currentPage,
        pageSize,
        showSizeChanger: true,
        pageSizeOptions: ['50', '100', '200'],
        showTotal: (total) => `共 ${total} 行`,
        onChange: (page, nextPageSize) => {
          setCurrentPage(nextPageSize !== pageSize ? 1 : page);
          setPageSize(nextPageSize);
        }
      }}
      scroll={{ x: true, y: fill ? '100%' : 'calc(100vh - 440px)' }}
    />
  );
}
