import { useLayoutEffect, useRef, useState } from 'react';

export interface TableViewportHeightOptions {
  enabled?: boolean;
  fallbackHeaderHeight?: number;
  reservedHeight?: number;
}

/**
 * Returns no height until a table viewport is actually visible. This prevents
 * inactive tabs and not-yet-laid-out flex panes from being locked to a tiny
 * fallback body height.
 */
export function useTableViewportHeight({
  enabled = true,
  fallbackHeaderHeight = 40,
  reservedHeight = 0
}: TableViewportHeightOptions = {}) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const [scrollY, setScrollY] = useState<number>();

  useLayoutEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport || !enabled) {
      setScrollY(undefined);
      return;
    }

    let animationFrame = 0;

    const measure = () => {
      animationFrame = 0;
      const viewportBounds = viewport.getBoundingClientRect();
      if (viewport.getClientRects().length === 0 || viewportBounds.width <= 0 || viewportBounds.height <= 0) {
        setScrollY(undefined);
        return;
      }

      const headerBounds = viewport.querySelector<HTMLElement>('.ant-table-thead')?.getBoundingClientRect();
      const headerHeight = headerBounds && headerBounds.height > 0
        ? headerBounds.height
        : fallbackHeaderHeight;
      const nextHeight = calculateTableScrollHeight(viewportBounds.height, headerHeight, reservedHeight);
      setScrollY((current) => current === nextHeight ? current : nextHeight);
    };

    const scheduleMeasure = () => {
      if (animationFrame) cancelAnimationFrame(animationFrame);
      animationFrame = requestAnimationFrame(measure);
    };

    scheduleMeasure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(scheduleMeasure);
    observer?.observe(viewport);
    const tableHeader = viewport.querySelector<HTMLElement>('.ant-table-thead');
    if (tableHeader) observer?.observe(tableHeader);
    window.addEventListener('resize', scheduleMeasure);

    return () => {
      if (animationFrame) cancelAnimationFrame(animationFrame);
      observer?.disconnect();
      window.removeEventListener('resize', scheduleMeasure);
    };
  }, [enabled, fallbackHeaderHeight, reservedHeight]);

  return { viewportRef, scrollY };
}

export function calculateTableScrollHeight(viewportHeight: number, headerHeight: number, reservedHeight = 0) {
  if (!Number.isFinite(viewportHeight) || !Number.isFinite(headerHeight) || viewportHeight <= 0 || headerHeight < 0) {
    return undefined;
  }
  const availableHeight = Math.floor(viewportHeight - headerHeight - Math.max(0, reservedHeight));
  return availableHeight > 0 ? availableHeight : undefined;
}
