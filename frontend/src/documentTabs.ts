/**
 * 工作区标签条的纯逻辑：标签上写什么、放不下时怎么截、左右箭头什么时候可用。
 *
 * <p>用户反馈打开多个对象之后标签分不清。以前标签写的是「SCHEMA.表名 · 数据」：同一个 Schema
 * 下的表前缀一模一样，长名字在末尾被截掉 —— 而末尾恰恰是区分两张表的那一段
 * （YY_FBIP_DATACENTER.BOND_BA_BONDINFO 和 YY_FBIP_DATACENTER.BOND_BA_CREDIT）。
 * 所以这里做两件事：Schema 只在同名表跨 Schema 时才写出来；超长的名字从中间截，头尾都留着。</p>
 */

export type DocumentTabSource = {
  key: string;
  kind: 'table' | 'object' | 'schemaObject';
  schemaName?: string;
  name: string;
  /** 标签上的类型字样；视图、存储过程这类对象写自己的类型（「存储过程」），不写笼统的「结构」。 */
  kindLabel?: string;
};

export type DocumentTabLabel = {
  key: string;
  /** 标签上显示的名字，可能已从中间截断。 */
  name: string;
  /** 只在需要靠它区分同名对象时才有值。 */
  schema?: string;
  kindLabel: string;
  /** 悬停提示：完整的限定名和视图类型，截断后靠它看全。 */
  title: string;
};

/** 标签上名字的字符上限。表名多是 ASCII，28 个字符大约 200px，一行能并排放下四五个。 */
export const DOCUMENT_TAB_NAME_MAX = 28;

/**
 * 从中间截断：保留开头和结尾，中间用省略号。
 *
 * <p>表名的公共部分通常在前面（前缀、业务域），区分彼此的部分在后面，尾部截断会把
 * 能区分它们的那一段截掉。结尾比开头多留一点，原因相同。</p>
 */
export function middleEllipsis(text: string, max = DOCUMENT_TAB_NAME_MAX): string {
  const chars = Array.from(text);
  if (chars.length <= max) return text;
  if (max <= 1) return '…';
  const keep = max - 1;
  const head = Math.floor(keep * 0.4);
  const tail = keep - head;
  return `${chars.slice(0, head).join('')}…${chars.slice(chars.length - tail).join('')}`;
}

export function documentTabLabels(documents: DocumentTabSource[], max = DOCUMENT_TAB_NAME_MAX): DocumentTabLabel[] {
  // 同名（不分大小写）且出现在不止一个 Schema 里，才需要把 Schema 写上标签。
  const schemasByName = new Map<string, Set<string>>();
  for (const document of documents) {
    const name = document.name.toLocaleLowerCase();
    const schemas = schemasByName.get(name) ?? new Set<string>();
    schemas.add((document.schemaName || '').toLocaleLowerCase());
    schemasByName.set(name, schemas);
  }
  return documents.map((document) => {
    const kindLabel = document.kindLabel ?? (document.kind === 'table' ? '数据' : '结构');
    const ambiguous = (schemasByName.get(document.name.toLocaleLowerCase())?.size ?? 0) > 1;
    const qualified = document.schemaName ? `${document.schemaName}.${document.name}` : document.name;
    return {
      key: document.key,
      name: middleEllipsis(document.name, max),
      schema: ambiguous && document.schemaName ? middleEllipsis(document.schemaName, 16) : undefined,
      kindLabel,
      title: `${qualified} · ${kindLabel}`
    };
  });
}

export type TabScrollState = { overflowing: boolean; canScrollLeft: boolean; canScrollRight: boolean };

/** 亚像素滚动位置（缩放、高分屏）会留下零点几像素的余量，不能因此把箭头一直亮着。 */
const SCROLL_EPSILON = 1;

export function tabScrollState(scrollLeft: number, clientWidth: number, scrollWidth: number): TabScrollState {
  const overflowing = scrollWidth - clientWidth > SCROLL_EPSILON;
  return {
    overflowing,
    canScrollLeft: overflowing && scrollLeft > SCROLL_EPSILON,
    canScrollRight: overflowing && scrollLeft + clientWidth < scrollWidth - SCROLL_EPSILON
  };
}

/**
 * 点一下箭头滚到哪里：一次翻大约八成可视宽度，留一截上一屏的标签作为参照，
 * 不至于翻过去之后找不到刚才看到哪儿了。结果夹在合法范围内。
 */
export function tabScrollTarget(scrollLeft: number, clientWidth: number, scrollWidth: number, direction: 'left' | 'right'): number {
  const step = Math.max(120, Math.round(clientWidth * 0.8));
  const max = Math.max(0, scrollWidth - clientWidth);
  const next = direction === 'left' ? scrollLeft - step : scrollLeft + step;
  return Math.min(max, Math.max(0, next));
}

/**
 * 鼠标滚轮换算成横向滚动量。没有触控板的用户只有竖向滚轮，标签条不认它的话，
 * 溢出的标签除了点箭头就够不着了。已经是横向手势（触控板、Shift+滚轮）的原样放过。
 */
export function wheelToHorizontal(deltaX: number, deltaY: number, deltaMode = 0): number {
  if (Math.abs(deltaX) >= Math.abs(deltaY)) return 0;
  // deltaMode 1 是按行计（Firefox 的鼠标滚轮），一行折成 40px。
  return deltaMode === 1 ? deltaY * 40 : deltaY;
}
