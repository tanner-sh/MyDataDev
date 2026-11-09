import { createContext, useContext, useLayoutEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/**
 * 顶栏留给当前工作区的两个位置。
 *
 * <p>toolbar 在顶栏正中间，放当前页签的操作按钮（SQL 的执行 / 格式化，表的筛选 / 导出，对象的调用 / 保存……）；
 * context 紧挨着连接选择器，放「在哪执行」这一类上下文（SQL 标签的 Schema）—— 连接和 Schema 是同一件事，
 * 分在顶栏两头就得左右扫两次才看得全，生产库上这一眼尤其要紧。</p>
 *
 * <p>为什么所有工作区都停靠，而不是只有 SQL：只停 SQL 的话，切到表页签按钮就回到工作区那一行，
 * 顶栏中间空一大块，同一个人一会儿往上找、一会儿往下找。窄屏（App 的 compactLayout）顶栏放不下，
 * 两个位置都是 null，各工作区照旧渲染自己那一行工具栏。</p>
 */
export type HeaderSlots = { toolbar: HTMLElement | null; context: HTMLElement | null };

const EMPTY_SLOTS: HeaderSlots = { toolbar: null, context: null };
export const HeaderSlotsContext = createContext<HeaderSlots>(EMPTY_SLOTS);

export function useHeaderSlots(): HeaderSlots {
  return useContext(HeaderSlotsContext);
}

/** 顶栏有没有给工作区留位置：有就停靠，没有就用工作区自己那一行。 */
export function useHeaderDocked(): boolean {
  return useHeaderSlots().toolbar !== null;
}

/**
 * 把按钮渲染进顶栏的某个位置。active 为 false 时什么都不渲染 —— 隐藏的文档页签一直挂载着，
 * 它们要是也往顶栏里放，同一个位置会叠出好几组按钮。
 */
export function HeaderDock({ slot, active = true, children }: { slot: keyof HeaderSlots; active?: boolean; children: ReactNode }) {
  const element = useHeaderSlots()[slot];
  if (!element || !active) return null;
  return createPortal(children, element);
}

/**
 * 顶栏操作区的可用宽度（ResizeObserver）。表工具栏据此决定要不要把浏览类操作收进「更多」：
 * 停靠之后，约束它的是顶栏中间那一格，而不是工作区自己有多宽。
 */
export function useDockWidth(): number | undefined {
  const element = useHeaderSlots().toolbar;
  const [width, setWidth] = useState<number>();
  useLayoutEffect(() => {
    if (!element) return;
    const measure = () => {
      const next = element.getBoundingClientRect().width;
      if (next > 0) setWidth((current) => current === next ? current : next);
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(element);
    return () => observer?.disconnect();
  }, [element]);
  return element ? width : undefined;
}
