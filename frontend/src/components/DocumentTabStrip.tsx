import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { CloseOutlined, CodeOutlined, LeftOutlined, ProfileOutlined, RightOutlined, TableOutlined } from '@ant-design/icons';
import { documentTabLabels, tabScrollState, tabScrollTarget, wheelToHorizontal, type DocumentTabSource, type TabScrollState } from '../documentTabs';

export type DocumentTabItem = DocumentTabSource & { dirty: boolean };

type DocumentTabStripProps = {
  /** 当前激活的标签：'sql' 表示 SQL 工作台，其余是文档键。 */
  activeKey: string;
  documents: DocumentTabItem[];
  disabled?: boolean;
  onSelectSql: () => void;
  onSelect: (key: string) => void;
  onClose: (key: string) => void;
};

/**
 * 工作区标签条。
 *
 * <p>放不下时左右两端出现箭头 —— 以前只能靠触控板横向滑动，用鼠标的人够不着溢出的标签。
 * 竖向滚轮也换算成横向滚动；切换标签时把激活的那个滚进可视区。激活态用实底色 + 底部主色条
 * 区分，而不是只靠按钮主色：一排标签里只有一个是「当前」，这件事必须一眼看出来。</p>
 */
export const DocumentTabStrip = memo(function DocumentTabStrip({ activeKey, documents, disabled = false, onSelectSql, onSelect, onClose }: DocumentTabStripProps) {
  const scrollerRef = useRef<HTMLDivElement>(null);
  const [scroll, setScroll] = useState<TabScrollState>({ overflowing: false, canScrollLeft: false, canScrollRight: false });
  const labels = useMemo(() => documentTabLabels(documents), [documents]);

  const measure = useCallback(() => {
    const node = scrollerRef.current;
    if (!node) return;
    const next = tabScrollState(node.scrollLeft, node.clientWidth, node.scrollWidth);
    setScroll((current) => current.overflowing === next.overflowing && current.canScrollLeft === next.canScrollLeft && current.canScrollRight === next.canScrollRight ? current : next);
  }, []);

  useLayoutEffect(() => {
    const node = scrollerRef.current;
    if (!node) return;
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(measure);
    observer?.observe(node);
    // 标签增删改变的是内容宽度而不是容器宽度，单观察容器量不到，内容也要观察。
    if (node.firstElementChild) observer?.observe(node.firstElementChild);
    return () => observer?.disconnect();
  }, [measure]);

  // React 的 onWheel 是被动监听，调不了 preventDefault；不拦的话页面会跟着竖向滚。
  useEffect(() => {
    const node = scrollerRef.current;
    if (!node) return;
    const handleWheel = (event: WheelEvent) => {
      if (node.scrollWidth <= node.clientWidth) return;
      const delta = wheelToHorizontal(event.deltaX, event.deltaY, event.deltaMode);
      if (delta === 0) return;
      event.preventDefault();
      node.scrollLeft += delta;
    };
    node.addEventListener('wheel', handleWheel, { passive: false });
    return () => node.removeEventListener('wheel', handleWheel);
  }, []);

  /*
    把激活的标签滚进可视区。依赖里带上 overflowing：表工作区按表重建，标签条随之重新挂载，
    第一次滚动发生在箭头出现之前 —— 箭头一出来就把滚动区压窄 24px×2，刚滚到最右的那个
    激活标签又被盖住一截。箭头出现后再滚一次。
  */
  useEffect(() => {
    const node = scrollerRef.current;
    const active = node?.querySelector<HTMLElement>('[data-tab-active="true"]');
    active?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
    measure();
  }, [activeKey, documents.length, scroll.overflowing, measure]);

  const scrollBy = (direction: 'left' | 'right') => {
    const node = scrollerRef.current;
    if (!node) return;
    const left = tabScrollTarget(node.scrollLeft, node.clientWidth, node.scrollWidth, direction);
    if (typeof node.scrollTo === 'function') node.scrollTo({ left, behavior: 'smooth' });
    else node.scrollLeft = left;
  };

  const sqlActive = activeKey === 'sql';
  return (
    <div className="document-tab-strip" data-overflowing={scroll.overflowing ? 'true' : undefined}>
      {scroll.overflowing && (
        <button type="button" className="document-tab-arrow" aria-label="向左滚动标签" title="向左滚动标签" disabled={!scroll.canScrollLeft} onClick={() => scrollBy('left')}>
          <LeftOutlined />
        </button>
      )}
      <div ref={scrollerRef} className="resource-document-tabs" onScroll={measure}>
        <nav className="document-tab-list" role="tablist" aria-label="工作区标签">
          <button type="button" role="tab" aria-selected={sqlActive} data-tab-active={sqlActive ? 'true' : undefined} className={`document-tab is-sql${sqlActive ? ' is-active' : ''}`} onClick={onSelectSql}>
            <CodeOutlined className="document-tab-icon" />
            <span className="document-tab-name">SQL 工作台</span>
          </button>
          {labels.map((label, index) => {
            const document = documents[index];
            const active = activeKey === document.key;
            return (
              <span key={label.key} className={`resource-document-tab document-tab-item${active ? ' is-active' : ''}`} data-tab-active={active ? 'true' : undefined}>
                <button
                  type="button"
                  role="tab"
                  aria-selected={active}
                  className={`document-tab is-${document.kind}${active ? ' is-active' : ''}`}
                  title={document.dirty ? `${label.title}（有未提交修改）` : label.title}
                  disabled={disabled}
                  onClick={() => onSelect(document.key)}
                  // 中键关闭，与浏览器标签一致。
                  onAuxClick={(event) => {
                    if (event.button !== 1 || disabled) return;
                    event.preventDefault();
                    onClose(document.key);
                  }}
                >
                  {document.kind === 'table' ? <TableOutlined className="document-tab-icon" /> : <ProfileOutlined className="document-tab-icon" />}
                  {label.schema && <span className="document-tab-schema">{label.schema}.</span>}
                  <span className="document-tab-name">{label.name}</span>
                  <span className="document-tab-kind">{label.kindLabel}</span>
                  {document.dirty && <span className="document-tab-dirty" aria-label="有未提交修改">●</span>}
                </button>
                <button
                  type="button"
                  className="document-tab-close"
                  disabled={disabled}
                  aria-label={`关闭 ${document.name} ${label.kindLabel}标签`}
                  title="关闭（中键单击标签也可关闭）"
                  onClick={() => onClose(document.key)}
                >
                  <CloseOutlined />
                </button>
              </span>
            );
          })}
        </nav>
      </div>
      {scroll.overflowing && (
        <button type="button" className="document-tab-arrow" aria-label="向右滚动标签" title="向右滚动标签" disabled={!scroll.canScrollRight} onClick={() => scrollBy('right')}>
          <RightOutlined />
        </button>
      )}
    </div>
  );
});
