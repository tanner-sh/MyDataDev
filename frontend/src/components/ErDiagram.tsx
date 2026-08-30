import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Segmented, Select, Space, Tooltip, Typography } from 'antd';
import { ReloadOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { api } from '../api';
import { PanelEmpty, PanelLoading } from './PanelState';
import { buildErModel, NODE_HEADER_HEIGHT, NODE_ROW_HEIGHT, type ErEdge, type ErNode } from '../erDiagram';
import type { SchemaDiagram } from '../types';

const { Text } = Typography;

const ZOOM_STEPS = [0.5, 0.75, 1, 1.25, 1.5];
const TABLE_LIMITS = [30, 60, 100, 150];

/**
 * Schema ER 图。
 *
 * 自绘 SVG，不引图表库 —— 首屏体积预算是硬约束，而这张图需要的只是矩形、折线和文字。
 * 布局全在 erDiagram.ts 里算好，这里只负责把坐标画出来并处理缩放与平移。
 */
export const ErDiagram = memo(function ErDiagram({
  connectionId,
  schemaName
}: {
  connectionId: number;
  schemaName?: string;
}) {
  const [diagram, setDiagram] = useState<SchemaDiagram>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [zoom, setZoom] = useState(1);
  const [limit, setLimit] = useState(60);
  const [highlighted, setHighlighted] = useState<string>();
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    const params = new URLSearchParams({ limit: String(limit) });
    if (schemaName) params.set('schemaName', schemaName);
    api<SchemaDiagram>(`/metadata/${connectionId}/diagram?${params}`)
      .then((result) => {
        if (!cancelled) setDiagram(result);
      })
      .catch((cause) => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : '加载 ER 图失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [connectionId, schemaName, limit]);

  const model = useMemo(() => (diagram ? buildErModel(diagram) : undefined), [diagram]);

  if (loading && !model) return <PanelLoading text="正在读取表结构与外键" />;
  if (error) return <Alert type="error" showIcon message={error} />;
  if (!model || model.nodes.length === 0) {
    return <PanelEmpty title="没有可绘制的表" description="这个 Schema 里没有表，或当前账号看不到它们。" />;
  }

  const isolated = model.nodes.length - model.connectedTables;

  return (
    <div className="er-diagram">
      <header className="er-diagram-toolbar">
        <Space size={8} wrap>
          <Text type="secondary">
            {model.nodes.length} 张表 · {model.edges.length} 条关系
            {isolated > 0 && ` · ${isolated} 张无外键关联`}
          </Text>
          {diagram?.truncated && (
            <Text type="warning">
              共 {diagram.totalTables} 张表，只画了前 {model.nodes.length} 张
            </Text>
          )}
        </Space>
        <Space size={8}>
          <Select
            size="small"
            value={limit}
            onChange={setLimit}
            options={TABLE_LIMITS.map((value) => ({ value, label: `最多 ${value} 张表` }))}
          />
          <Segmented
            size="small"
            value={zoom}
            onChange={(value) => setZoom(Number(value))}
            options={ZOOM_STEPS.map((value) => ({ value, label: `${Math.round(value * 100)}%` }))}
          />
          <Tooltip title="放大">
            <Button
              size="small"
              icon={<ZoomInOutlined />}
              disabled={zoom >= ZOOM_STEPS[ZOOM_STEPS.length - 1]}
              onClick={() => setZoom((current) => ZOOM_STEPS[Math.min(ZOOM_STEPS.indexOf(current) + 1, ZOOM_STEPS.length - 1)])}
            />
          </Tooltip>
          <Tooltip title="缩小">
            <Button
              size="small"
              icon={<ZoomOutOutlined />}
              disabled={zoom <= ZOOM_STEPS[0]}
              onClick={() => setZoom((current) => ZOOM_STEPS[Math.max(ZOOM_STEPS.indexOf(current) - 1, 0)])}
            />
          </Tooltip>
          <Tooltip title="重新读取结构">
            <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={() => setLimit((value) => value)} />
          </Tooltip>
        </Space>
      </header>
      <div className="er-diagram-canvas" ref={scrollRef}>
        <svg
          width={model.width * zoom}
          height={model.height * zoom}
          viewBox={`0 0 ${model.width} ${model.height}`}
          role="img"
          aria-label={`${diagram?.schemaName || ''} Schema 的实体关系图`}
        >
          <defs>
            <marker id="er-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
              <path d="M0,0 L8,4 L0,8 z" fill="var(--text-secondary)" opacity="0.55" />
            </marker>
            <marker id="er-arrow-active" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
              <path d="M0,0 L8,4 L0,8 z" fill="var(--primary)" />
            </marker>
          </defs>
          {model.edges.map((edge) => (
            <EdgeLine key={edge.id} edge={edge} highlighted={isEdgeActive(edge, highlighted)} />
          ))}
          {model.nodes.map((node) => (
            <TableBox
              key={node.name}
              node={node}
              highlighted={highlighted === node.name}
              onHover={setHighlighted}
            />
          ))}
        </svg>
      </div>
    </div>
  );
});

function isEdgeActive(edge: ErEdge, highlighted?: string): boolean {
  return Boolean(highlighted) && (edge.from === highlighted || edge.to === highlighted);
}

function EdgeLine({ edge, highlighted }: { edge: ErEdge; highlighted: boolean }) {
  if (edge.points.length === 0) return null;
  const path = edge.points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x},${point.y}`).join(' ');
  return (
    <path
      d={path}
      className={highlighted ? 'er-edge er-edge-active' : 'er-edge'}
      markerEnd={highlighted ? 'url(#er-arrow-active)' : 'url(#er-arrow)'}
    >
      <title>
        {edge.from} → {edge.to}
        {'\n'}
        {edge.columns.map((column) => `${column.fromColumn} → ${column.toColumn}`).join('\n')}
      </title>
    </path>
  );
}

function TableBox({
  node,
  highlighted,
  onHover
}: {
  node: ErNode;
  highlighted: boolean;
  onHover: (name?: string) => void;
}) {
  return (
    <g
      className={highlighted ? 'er-node er-node-active' : 'er-node'}
      onMouseEnter={() => onHover(node.name)}
      onMouseLeave={() => onHover(undefined)}
    >
      <rect x={node.x} y={node.y} width={node.width} height={node.height} rx={6} className="er-node-body" />
      <rect x={node.x} y={node.y} width={node.width} height={NODE_HEADER_HEIGHT} rx={6} className="er-node-header" />
      <text x={node.x + 10} y={node.y + 20} className="er-node-title">
        {truncate(node.name, 24)}
        <title>{`${node.schemaName ? `${node.schemaName}.` : ''}${node.name} · 共 ${node.columnCount} 列`}</title>
      </text>
      {node.columns.map((column, index) => {
        const y = node.y + NODE_HEADER_HEIGHT + index * NODE_ROW_HEIGHT + 14;
        return (
          <text key={column.name} x={node.x + 10} y={y} className="er-node-column">
            <tspan className={column.primaryKey ? 'er-key-primary' : 'er-key-foreign'}>
              {column.primaryKey ? 'PK' : 'FK'}
            </tspan>
            <tspan dx={6}>{truncate(column.name, 18)}</tspan>
            <title>{`${column.name} · ${column.type}${column.nullable ? '' : ' NOT NULL'}`}</title>
          </text>
        );
      })}
      {node.hiddenColumnCount > 0 && (
        <text
          x={node.x + 10}
          y={node.y + node.height - 6}
          className="er-node-more"
        >
          另有 {node.hiddenColumnCount} 列
        </text>
      )}
    </g>
  );
}

function truncate(value: string, limit: number): string {
  return value.length <= limit ? value : `${value.slice(0, limit - 1)}…`;
}
