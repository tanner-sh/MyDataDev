import { useLayoutEffect, useRef, useState } from 'react';

export interface TableViewportHeightOptions {
  enabled?: boolean;
  active?: boolean;
  fallbackHeaderHeight?: number;
  reservedHeight?: number;
}

/**
 * Measures a visible table viewport without dropping the last valid height
 * while its tab is hidden. Consumers can wait for scrollY before mounting a
 * large table so the first render is virtualized as well.
 */
export function useTableViewportHeight({
  enabled = true,
  active = true,
  fallbackHeaderHeight = 40,
  reservedHeight = 0
}: TableViewportHeightOptions = {}) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const [scrollY, setScrollY] = useState<number>();

  useLayoutEffect(() => {
    const viewport = viewportRef.current;
    if (!enabled) {
      setScrollY(undefined);
      return;
    }
    if (!viewport || !active) return;

    let animationFrame = 0;
    let observedHeader: HTMLElement | null = null;
    let mutationObserver: MutationObserver | null = null;
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => scheduleMeasure());

    const measure = () => {
      animationFrame = 0;
      const viewportBounds = viewport.getBoundingClientRect();
      if (viewport.getClientRects().length === 0 || viewportBounds.width <= 0 || viewportBounds.height <= 0) {
        return;
      }

      const tableHeader = viewport.querySelector<HTMLElement>('.ant-table-thead');
      if (tableHeader !== observedHeader) {
        if (observedHeader) observer?.unobserve(observedHeader);
        observedHeader = tableHeader;
        if (observedHeader) observer?.observe(observedHeader);
      }
      if (tableHeader && mutationObserver) {
        mutationObserver.disconnect();
        mutationObserver = null;
      }
      const headerBounds = tableHeader?.getBoundingClientRect();
      const headerHeight = headerBounds && headerBounds.height > 0
        ? headerBounds.height
        : fallbackHeaderHeight;
      setScrollY((current) => nextTableScrollHeight(current, viewportBounds.height, headerHeight, reservedHeight));
    };

    const scheduleMeasure = () => {
      if (animationFrame) cancelAnimationFrame(animationFrame);
      animationFrame = requestAnimationFrame(measure);
    };

    measure();
    observer?.observe(viewport);
    mutationObserver = typeof MutationObserver === 'undefined'
      ? null
      : new MutationObserver(scheduleMeasure);
    mutationObserver?.observe(viewport, { childList: true, subtree: true });
    window.addEventListener('resize', scheduleMeasure);

    return () => {
      if (animationFrame) cancelAnimationFrame(animationFrame);
      observer?.disconnect();
      mutationObserver?.disconnect();
      window.removeEventListener('resize', scheduleMeasure);
    };
  }, [active, enabled, fallbackHeaderHeight, reservedHeight]);

  return { viewportRef, scrollY };
}

export function calculateTableScrollHeight(viewportHeight: number, headerHeight: number, reservedHeight = 0) {
  if (!Number.isFinite(viewportHeight) || !Number.isFinite(headerHeight) || viewportHeight <= 0 || headerHeight < 0) {
    return undefined;
  }
  const availableHeight = Math.floor(viewportHeight - headerHeight - Math.max(0, reservedHeight));
  return availableHeight > 0 ? availableHeight : undefined;
}

export function nextTableScrollHeight(current: number | undefined, viewportHeight: number, headerHeight: number, reservedHeight = 0) {
  return calculateTableScrollHeight(viewportHeight, headerHeight, reservedHeight) ?? current;
}
