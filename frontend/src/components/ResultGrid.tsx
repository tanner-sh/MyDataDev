import { memo, useEffect, useMemo, useState } from 'react';
import { Empty, Pagination, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTableViewportHeight } from '../hooks/useTableViewportHeight';
import type { ResultRow, SqlResult } from '../types';

const DEFAULT_PAGE_SIZE = 50;
const PAGE_SIZE_OPTIONS = [20, 50, 100, 200, 500];

export const ResultGrid = memo(function ResultGrid({ result, fill = false }: { result: SqlResult | null; fill?: boolean }) {
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const { viewportRef, scrollY } = useTableViewportHeight({ enabled: Boolean(result?.resultSet) });
  const emptyClassName = fill ? 'empty-state empty-state-fill' : 'empty-state';
  const rowCount = result?.resultSet ? result.rows.length : 0;

  useEffect(() => {
    setCurrentPage(1);
  }, [result]);

  useEffect(() => {
    const lastPage = Math.max(1, Math.ceil(rowCount / pageSize));
    setCurrentPage((page) => Math.min(page, lastPage));
  }, [pageSize, rowCount]);

  const columns = useMemo<ColumnsType<ResultRow>>(() => {
    if (!result?.resultSet) return [];
    return [
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
        width: Math.max(140, Math.min(280, column.length * 14 + 48)),
        ellipsis: true,
        render: renderCellValue
      }))
    ];
  }, [currentPage, pageSize, result]);

  const rows = useMemo<ResultRow[]>(() => {
    if (!result?.resultSet) return [];
    const start = (currentPage - 1) * pageSize;
    return result.rows.slice(start, start + pageSize).map((row, index) => ({
      ...row,
      key: String(start + index)
    }));
  }, [currentPage, pageSize, result]);

  if (!result) return <Empty className={emptyClassName} description="执行查询后查看结果。" />;
  if (!result.resultSet) return <Empty className={emptyClassName} description={`影响 ${result.affectedRows} 行。`} />;

  return (
    <div className={`result-grid-shell${fill ? ' result-grid-shell-fill' : ''}`}>
      <div ref={viewportRef} className="data-grid-viewport">
        <Table<ResultRow>
          size="small"
          className="data-grid data-grid-fill result-grid"
          columns={columns}
          dataSource={rows}
          pagination={false}
          scroll={{ x: 'max-content', ...(scrollY ? { y: scrollY } : {}) }}
        />
      </div>
      <div className="grid-pagination result-grid-pagination">
        <Pagination
          size="small"
          current={currentPage}
          pageSize={pageSize}
          total={rowCount}
          pageSizeOptions={PAGE_SIZE_OPTIONS}
          showSizeChanger
          showQuickJumper
          showLessItems
          responsive
          showTotal={(total, range) => total === 0 ? '共 0 行' : `第 ${range[0]}-${range[1]} 行，共 ${total} 行`}
          onChange={(page, nextPageSize) => {
            setCurrentPage(nextPageSize === pageSize ? page : 1);
            setPageSize(nextPageSize);
          }}
        />
      </div>
    </div>
  );
});

function renderCellValue(value: unknown) {
  if (value == null) return <span className="cell-null">NULL</span>;
  if (value === '') return <span className="cell-empty">空字符串</span>;
  const text = String(value);
  return <span className="grid-cell-value" title={text}>{text}</span>;
}
