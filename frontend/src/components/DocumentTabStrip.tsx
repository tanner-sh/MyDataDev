import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Button, Dropdown, Tooltip, type MenuProps } from 'antd';
import { CloseOutlined, CodeOutlined, FunctionOutlined, ConsoleSqlOutlined, DownOutlined, LeftOutlined, PlusOutlined, ProfileOutlined, RightOutlined, TableOutlined } from '@ant-design/icons';
import { documentTabLabels, tabScrollState, tabScrollTarget, wheelToHorizontal, type DocumentTabSource, type TabConnectionBadge, type TabScrollState } from '../documentTabs';

export type DocumentTabItem = DocumentTabSource & { dirty: boolean; connection?: TabConnectionBadge };

/** SQL 查询标签（含命令窗口）。 */
export type SqlDocumentTabItem = { id: string; title: string; dirty: boolean; command: boolean; connection?: TabConnectionBadge };

/**
 * 标签上的连接标记。不同连接的标签平铺在一条上，名字说明「这是哪个库」，红色「生产」说明「小心」，
 * 「不可用」说明这条连接没了 —— 标签留着草稿，但要先换绑才能执行。
 */
function TabConnectionMark({ badge }: { badge?: TabConnectionBadge }) {
  if (!badge) return null;
  return <>
    {badge.unavailable && <span className="document-tab-connection is-unavailable" title="这个标签绑定的连接已不可用（被删除或没有权限），请用顶栏的连接选择器换绑">不可用</span>}
    {!badge.unavailable && badge.name && <span className="document-tab-connection" title={badge.fullName}>{badge.name}</span>}
    {badge.production && <span className="document-tab-production" title={`生产连接：${badge.fullName ?? ''}`}>生产</span>}
  </>;
}

function connectionTitle(badge?: TabConnectionBadge) {
  if (!badge) return '';
  if (badge.unavailable) return ' · 连接不可用';
  return badge.fullName ? ` · ${badge.fullName}${badge.production ? '（生产）' : ''}` : '';
}

type DocumentTabStripProps = {
  /** 当前激活的标签：'sql' 表示 SQL 工作台（具体是哪个查询标签看 activeSqlTabId），其余是文档键。 */
  activeKey: string;
  sqlTabs: SqlDocumentTabItem[];
  activeSqlTabId: string;
  documents: DocumentTabItem[];
  disabled?: boolean;
  /** 新建查询标签按钮的提示（带快捷键）。 */
  addSqlTabHint: string;
  sqlTabMenu: MenuProps;
  onSelectSql: (tabId: string) => void;
  onAddSql: () => void;
  onCloseSql: (tabId: string) => void;
  onRenameSql: (tabId: string) => void;
  onSelect: (key: string) => void;
  onClose: (key: string) => void;
};

/**
 * 工作区标签条：SQL 查询标签与打开的表、对象排在同一条上。
 *
 * <p>以前这里只有一个「SQL 工作台」，查询标签在它下面另起一层 —— 两层标签各管各的，用户得先
 * 想清楚「我要的东西在哪一层」。现在一条标签条，查询、命令窗口、表数据、对象结构都是平级的标签。</p>
 *
 * <p>放不下时左右两端出现箭头 —— 以前只能靠触控板横向滑动，用鼠标的人够不着溢出的标签。
 * 竖向滚轮也换算成横向滚动；切换标签时把激活的那个滚进可视区。激活态用实底色 + 底部主色条
 * 区分，而不是只靠按钮主色：一排标签里只有一个是「当前」，这件事必须一眼看出来。</p>
 */
export const DocumentTabStrip = memo(function DocumentTabStrip({ activeKey, sqlTabs, activeSqlTabId, documents, disabled = false, addSqlTabHint, sqlTabMenu, onSelectSql, onAddSql, onCloseSql, onRenameSql, onSelect, onClose }: DocumentTabStripProps) {
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
  }, [activeKey, activeSqlTabId, documents.length, sqlTabs.length, scroll.overflowing, measure]);

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
          {sqlTabs.map((tab) => {
            const active = sqlActive && tab.id === activeSqlTabId;
            const closable = sqlTabs.length > 1;
            return (
              <span key={tab.id} className={`document-tab-item is-sql-item${active ? ' is-active' : ''}`} data-tab-active={active ? 'true' : undefined}>
                <button
                  type="button"
                  role="tab"
                  aria-selected={active}
                  className={`document-tab is-sql${active ? ' is-active' : ''}`}
                  data-sql-current={tab.id === activeSqlTabId ? 'true' : undefined}
                  title={`${tab.title}${tab.command ? '（命令窗口）' : ''}${connectionTitle(tab.connection)}，双击重命名`}
                  onClick={() => onSelectSql(tab.id)}
                  onDoubleClick={(event) => { event.preventDefault(); onRenameSql(tab.id); }}
                  onAuxClick={(event) => {
                    if (event.button !== 1 || !closable) return;
                    event.preventDefault();
                    onCloseSql(tab.id);
                  }}
                >
                  {tab.command ? <CodeOutlined className="document-tab-icon" aria-label="命令窗口" /> : <ConsoleSqlOutlined className="document-tab-icon" />}
                  <TabConnectionMark badge={tab.connection} />
                  <span className="document-tab-name">{tab.title}</span>
                  {tab.dirty && <span className="document-tab-dirty" aria-label="包含会话草稿">●</span>}
                </button>
                {closable && (
                  <button
                    type="button"
                    className="document-tab-close"
                    aria-label={`关闭 ${tab.title} 查询标签`}
                    title="关闭（中键单击标签也可关闭）"
                    onClick={() => onCloseSql(tab.id)}
                  >
                    <CloseOutlined />
                  </button>
                )}
              </span>
            );
          })}
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
                  title={`${label.title}${connectionTitle(document.connection)}${document.dirty ? '（有未提交修改）' : ''}`}
                  disabled={disabled}
                  onClick={() => onSelect(document.key)}
                  // 中键关闭，与浏览器标签一致。
                  onAuxClick={(event) => {
                    if (event.button !== 1 || disabled) return;
                    event.preventDefault();
                    onClose(document.key);
                  }}
                >
                  {document.kind === 'table' ? <TableOutlined className="document-tab-icon" />
                    : document.kind === 'schemaObject' ? <FunctionOutlined className="document-tab-icon" />
                    : <ProfileOutlined className="document-tab-icon" />}
                  <TabConnectionMark badge={document.connection} />
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
      {/* 新建与标签操作放在滚动区外面：标签多到溢出时它们也得够得着。 */}
      <Tooltip title={addSqlTabHint}>
        <Button type="text" size="small" className="document-tab-action" icon={<PlusOutlined />} aria-label="新建 SQL 标签页" onClick={onAddSql} />
      </Tooltip>
      <Dropdown trigger={['click']} menu={sqlTabMenu}>
        <Button type="text" size="small" className="document-tab-action" icon={<DownOutlined />} aria-label="标签操作" title="新建命令窗口、找回关闭的 SQL、重命名与复制当前查询标签" />
      </Dropdown>
    </div>
  );
});
