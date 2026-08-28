import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Empty, Segmented, Select, Space, Typography } from 'antd';
import {
  buildChartModel,
  chartableColumns,
  formatChartNumber,
  MAX_SERIES,
  niceTicks,
  type ChartConfig,
  type ChartModel,
  type ChartType
} from '../resultChart';
import type { ResultColumn } from '../types';

const { Text } = Typography;

const CHART_TYPES: Array<{ value: ChartType; label: string }> = [
  { value: 'bar', label: '柱状图' },
  { value: 'line', label: '折线图' },
  { value: 'pie', label: '饼图' }
];

const HEIGHT = 320;
const PLOT_PADDING = { top: 16, right: 16, bottom: 44, left: 56 };
/** 柱子不填满整个槽位，留下的空气本身就是分隔。 */
const MAX_BAR_WIDTH = 24;
/** 相邻填充之间留 2px 表面色，靠间隙而不是描边来区分。 */
const MARK_GAP = 2;
/** 直接标注只在序列少、点也少的时候才上；每个点都标数字等于没标。 */
const MAX_DIRECT_LABEL_POINTS = 12;
const MAX_DIRECT_LABEL_SERIES = 4;

function seriesColor(slot: number): string {
  return `var(--chart-series-${(slot % MAX_SERIES) + 1})`;
}

/**
 * 查询结果的图表视图。
 *
 * 画的是当前已加载的这一批行，与表格视图共享同一份数据 —— 图表和表格是同一块区域的两个
 * 视图，这也是浅色主题下几个对比度偏低的色位可以成立的前提（值随时能在表格里读到）。
 *
 * 自绘 SVG 而不是引图表库：这里只需要三种最基本的图形，而首屏体积预算是硬约束。
 */
export const ResultChart = memo(function ResultChart({ columns, rows }: {
  columns: ResultColumn[];
  rows: unknown[][];
}) {
  const options = useMemo(() => chartableColumns(columns, rows), [columns, rows]);
  const signature = useMemo(() => columns.map((column) => column.key).join('|'), [columns]);
  const suggestion = useMemo(() => {
    const category = options.categories[0];
    const values = options.values.filter((column) => column.key !== category?.key).slice(0, 4);
    return category && values.length > 0
      ? { type: 'bar' as ChartType, categoryKey: category.key, valueKeys: values.map((column) => column.key) }
      : null;
  }, [options]);
  // 初值直接取猜测结果，而不是先 null 再用 effect 补：靠 effect 的话首帧会闪一下空状态。
  const [config, setConfig] = useState<ChartConfig | null>(suggestion);
  const containerRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(720);
  const [hover, setHover] = useState<{ index: number; x: number; y: number } | null>(null);

  // 列变了（换了一次查询）就重新猜一份配置，而不是把上一次的列名硬套过来。
  // 依赖只认列签名：suggestion 每次都是新对象，放进依赖会 setConfig -> 重渲 -> 新
  // suggestion -> 再 setConfig，转成死循环。
  const suggestionRef = useRef(suggestion);
  const signatureRef = useRef(signature);
  suggestionRef.current = suggestion;
  useEffect(() => {
    if (signatureRef.current === signature) return;
    signatureRef.current = signature;
    setConfig(suggestionRef.current);
  }, [signature]);

  useEffect(() => {
    const element = containerRef.current;
    if (!element || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(320, entry.contentRect.width)));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const model = useMemo(
    () => (config ? buildChartModel(columns, rows, config) : null),
    [columns, config, rows]
  );

  if (!config || !model) {
    return <Empty description="当前结果没有可用于绘图的数值列" />;
  }

  return (
    <div className="result-chart" ref={containerRef}>
      <Space size={8} wrap className="result-chart-toolbar">
        <Segmented
          size="small"
          value={config.type}
          options={CHART_TYPES}
          onChange={(value) => setConfig({ ...config, type: value as ChartType })}
        />
        <Select
          size="small"
          className="result-chart-select"
          value={config.categoryKey}
          options={options.categories.map((column) => ({ value: column.key, label: column.label }))}
          onChange={(value) => setConfig({ ...config, categoryKey: value })}
        />
        <Select
          size="small"
          mode="multiple"
          className="result-chart-select result-chart-values"
          placeholder="选择数值列"
          maxTagCount="responsive"
          value={config.valueKeys}
          options={options.values.map((column) => ({ value: column.key, label: column.label }))}
          onChange={(value) => setConfig({ ...config, valueKeys: value })}
        />
      </Space>

      {model.notices.map((notice) => (
        <Alert key={notice} type="info" showIcon className="result-chart-notice" message={notice} />
      ))}

      {model.series.length === 0 || model.categories.length === 0 ? (
        <Empty description="没有可绘制的数据" />
      ) : (
        <div className="result-chart-plot">
          {model.type === 'pie'
            ? <PieChart model={model} width={width} onHover={setHover} hover={hover} />
            : <CartesianChart model={model} width={width} onHover={setHover} hover={hover} />}
          {hover && <ChartTooltip model={model} hover={hover} />}
        </div>
      )}

      {model.series.length > 1 && (
        <ul className="result-chart-legend">
          {model.series.map((item) => (
            <li key={item.key}>
              <span className="result-chart-swatch" style={{ background: seriesColor(item.slot) }} aria-hidden="true" />
              <Text type="secondary">{item.label}</Text>
            </li>
          ))}
        </ul>
      )}
      {model.type === 'pie' && model.series.length === 1 && (
        <ul className="result-chart-legend">
          {model.categories.map((name, index) => (
            <li key={name}>
              <span className="result-chart-swatch" style={{ background: seriesColor(index) }} aria-hidden="true" />
              <Text type="secondary">{name}</Text>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
});

type HoverState = { index: number; x: number; y: number } | null;

function CartesianChart({ model, width, hover, onHover }: {
  model: ChartModel;
  width: number;
  hover: HoverState;
  onHover: (hover: HoverState) => void;
}) {
  const plotWidth = Math.max(1, width - PLOT_PADDING.left - PLOT_PADDING.right);
  const plotHeight = HEIGHT - PLOT_PADDING.top - PLOT_PADDING.bottom;
  const ticks = niceTicks(model.min, model.max);
  const domainMin = Math.min(...ticks);
  const domainMax = Math.max(...ticks);
  const span = domainMax - domainMin || 1;
  const y = (value: number) => PLOT_PADDING.top + plotHeight - ((value - domainMin) / span) * plotHeight;
  const bandWidth = plotWidth / model.categories.length;
  const zeroY = y(Math.min(Math.max(0, domainMin), domainMax));
  // 标签密到挤在一起就只画其中一部分，剩下的交给悬停提示。
  const labelStep = Math.max(1, Math.ceil(model.categories.length / Math.floor(plotWidth / 64)));
  const directLabels = model.categories.length <= MAX_DIRECT_LABEL_POINTS
    && model.series.length <= MAX_DIRECT_LABEL_SERIES;

  const pick = (event: React.MouseEvent<SVGSVGElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    const offsetX = event.clientX - bounds.left - PLOT_PADDING.left;
    const index = Math.floor(offsetX / bandWidth);
    if (index < 0 || index >= model.categories.length) {
      onHover(null);
      return;
    }
    onHover({ index, x: event.clientX - bounds.left, y: event.clientY - bounds.top });
  };

  return (
    <svg
      className="result-chart-svg"
      width={width}
      height={HEIGHT}
      role="img"
      aria-label={`${model.series.map((item) => item.label).join('、')} 按 ${model.categories.length} 个分类的图表`}
      onMouseMove={pick}
      onMouseLeave={() => onHover(null)}
    >
      {ticks.map((tick) => (
        <g key={tick}>
          <line
            className="result-chart-grid"
            x1={PLOT_PADDING.left}
            x2={PLOT_PADDING.left + plotWidth}
            y1={y(tick)}
            y2={y(tick)}
          />
          <text className="result-chart-axis-text" x={PLOT_PADDING.left - 8} y={y(tick) + 4} textAnchor="end">
            {formatChartNumber(tick)}
          </text>
        </g>
      ))}
      <line
        className="result-chart-axis"
        x1={PLOT_PADDING.left}
        x2={PLOT_PADDING.left + plotWidth}
        y1={zeroY}
        y2={zeroY}
      />

      {hover && (
        <line
          className="result-chart-crosshair"
          x1={PLOT_PADDING.left + (hover.index + 0.5) * bandWidth}
          x2={PLOT_PADDING.left + (hover.index + 0.5) * bandWidth}
          y1={PLOT_PADDING.top}
          y2={PLOT_PADDING.top + plotHeight}
        />
      )}

      {model.type === 'bar'
        ? model.series.map((item, seriesIndex) => {
          const slotWidth = Math.min(MAX_BAR_WIDTH, (bandWidth - MARK_GAP * 2) / model.series.length);
          return (
            <g key={item.key} fill={seriesColor(item.slot)}>
              {item.values.map((value, index) => {
                if (value == null) return null;
                const groupWidth = slotWidth * model.series.length + MARK_GAP * (model.series.length - 1);
                const x = PLOT_PADDING.left + index * bandWidth + (bandWidth - groupWidth) / 2
                  + seriesIndex * (slotWidth + MARK_GAP);
                const top = Math.min(y(value), zeroY);
                const height = Math.max(1, Math.abs(zeroY - y(value)));
                return (
                  <path
                    key={`${item.key}-${index}`}
                    d={barPath(x, top, slotWidth, height, value >= 0)}
                  />
                );
              })}
            </g>
          );
        })
        : model.series.map((item) => (
          <g key={item.key}>
            <path
              className="result-chart-line"
              d={linePath(item.values, (index) => PLOT_PADDING.left + (index + 0.5) * bandWidth, y)}
              stroke={seriesColor(item.slot)}
              fill="none"
            />
            {item.values.length <= MAX_DIRECT_LABEL_POINTS && item.values.map((value, index) => (
              value == null ? null : (
                <circle
                  key={`${item.key}-${index}`}
                  className="result-chart-marker"
                  cx={PLOT_PADDING.left + (index + 0.5) * bandWidth}
                  cy={y(value)}
                  r={4}
                  fill={seriesColor(item.slot)}
                />
              )
            ))}
          </g>
        ))}

      {directLabels && model.series.length === 1 && model.series[0].values.map((value, index) => (
        value == null ? null : (
          <text
            key={`label-${index}`}
            className="result-chart-value-label"
            x={PLOT_PADDING.left + (index + 0.5) * bandWidth}
            y={y(value) - 6}
            textAnchor="middle"
          >
            {formatChartNumber(value)}
          </text>
        )
      ))}

      {model.categories.map((name, index) => (
        index % labelStep === 0 ? (
          <text
            key={name + index}
            className="result-chart-axis-text"
            x={PLOT_PADDING.left + (index + 0.5) * bandWidth}
            y={HEIGHT - PLOT_PADDING.bottom + 20}
            textAnchor="middle"
          >
            {truncate(name, Math.max(4, Math.floor((bandWidth * labelStep) / 8)))}
          </text>
        ) : null
      ))}
    </svg>
  );
}

function PieChart({ model, width, hover, onHover }: {
  model: ChartModel;
  width: number;
  hover: HoverState;
  onHover: (hover: HoverState) => void;
}) {
  const values = model.series[0].values.map((value) => value ?? 0);
  const total = values.reduce((sum, value) => sum + value, 0);
  const radius = Math.min(HEIGHT, width) / 2 - 24;
  const centerX = width / 2;
  const centerY = HEIGHT / 2;
  if (total <= 0) return <Empty description="所选列的合计为 0，无法绘制饼图" />;

  let angle = -Math.PI / 2;
  return (
    <svg className="result-chart-svg" width={width} height={HEIGHT} role="img" aria-label="占比饼图">
      {values.map((value, index) => {
        const sweep = (value / total) * Math.PI * 2;
        const path = arcPath(centerX, centerY, radius, angle, angle + sweep);
        const midAngle = angle + sweep / 2;
        angle += sweep;
        const share = value / total;
        return (
          <g key={model.categories[index]}>
            <path
              d={path}
              fill={seriesColor(index)}
              // 2px 表面色描边就是相邻扇区之间的那道间隙，不是给标记加边框。
              className="result-chart-slice"
              onMouseMove={(event) => {
                const bounds = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
                if (!bounds) return;
                onHover({ index, x: event.clientX - bounds.left, y: event.clientY - bounds.top });
              }}
              onMouseLeave={() => onHover(null)}
            />
            {share >= 0.05 && (
              <text
                className="result-chart-value-label"
                x={centerX + Math.cos(midAngle) * radius * 0.68}
                y={centerY + Math.sin(midAngle) * radius * 0.68 + 4}
                textAnchor="middle"
              >
                {`${Math.round(share * 100)}%`}
              </text>
            )}
          </g>
        );
      })}
      {hover && <title>{model.categories[hover.index]}</title>}
    </svg>
  );
}

function ChartTooltip({ model, hover }: { model: ChartModel; hover: NonNullable<HoverState> }) {
  const isPie = model.type === 'pie';
  const entries = isPie
    ? [{ label: model.categories[hover.index], slot: hover.index, value: model.series[0].values[hover.index] }]
    : model.series.map((item) => ({ label: item.label, slot: item.slot, value: item.values[hover.index] }));
  return (
    <div
      className="result-chart-tooltip"
      style={{ left: Math.min(hover.x + 12, 9999), top: hover.y + 12 }}
      role="status"
    >
      {!isPie && <div className="result-chart-tooltip-title">{model.categories[hover.index]}</div>}
      {entries.map((entry) => (
        <div key={entry.label} className="result-chart-tooltip-row">
          <span className="result-chart-swatch" style={{ background: seriesColor(entry.slot) }} aria-hidden="true" />
          <span className="result-chart-tooltip-label">{entry.label}</span>
          <span className="result-chart-tooltip-value">{entry.value == null ? '—' : formatChartNumber(entry.value)}</span>
        </div>
      ))}
    </div>
  );
}

/** 数值那一端做 4px 圆角，基线那一端保持方角。 */
function barPath(x: number, top: number, barWidth: number, height: number, positive: boolean): string {
  const radius = Math.min(4, barWidth / 2, height);
  const bottom = top + height;
  return positive
    ? `M${x} ${bottom} L${x} ${top + radius} Q${x} ${top} ${x + radius} ${top}`
      + ` L${x + barWidth - radius} ${top} Q${x + barWidth} ${top} ${x + barWidth} ${top + radius}`
      + ` L${x + barWidth} ${bottom} Z`
    : `M${x} ${top} L${x} ${bottom - radius} Q${x} ${bottom} ${x + radius} ${bottom}`
      + ` L${x + barWidth - radius} ${bottom} Q${x + barWidth} ${bottom} ${x + barWidth} ${bottom - radius}`
      + ` L${x + barWidth} ${top} Z`;
}

/** 空值断开折线而不是插值：数据缺口不该被画成一条直线。 */
function linePath(values: Array<number | null>, x: (index: number) => number, y: (value: number) => number): string {
  let path = '';
  let pendingMove = true;
  values.forEach((value, index) => {
    if (value == null) {
      pendingMove = true;
      return;
    }
    path += `${pendingMove ? 'M' : 'L'}${x(index)} ${y(value)} `;
    pendingMove = false;
  });
  return path.trim();
}

function arcPath(centerX: number, centerY: number, radius: number, start: number, end: number): string {
  const startX = centerX + Math.cos(start) * radius;
  const startY = centerY + Math.sin(start) * radius;
  const endX = centerX + Math.cos(end) * radius;
  const endY = centerY + Math.sin(end) * radius;
  const largeArc = end - start > Math.PI ? 1 : 0;
  // 整圆用两段弧拼，否则起止点重合会画成空路径。
  if (end - start >= Math.PI * 2 - 1e-6) {
    return `M${centerX - radius} ${centerY} A${radius} ${radius} 0 1 1 ${centerX + radius} ${centerY}`
      + ` A${radius} ${radius} 0 1 1 ${centerX - radius} ${centerY} Z`;
  }
  return `M${centerX} ${centerY} L${startX} ${startY} A${radius} ${radius} 0 ${largeArc} 1 ${endX} ${endY} Z`;
}

function truncate(value: string, maxChars: number): string {
  return value.length <= maxChars ? value : `${value.slice(0, Math.max(1, maxChars - 1))}…`;
}
