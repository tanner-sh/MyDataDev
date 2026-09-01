import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';

export type PaneResizerDirection = 'horizontal' | 'vertical';
export type PaneResizerUnit = 'px' | 'ratio';

export interface PaneResizerProps {
  /** Horizontal changes a width along the X axis; vertical changes a height along the Y axis. */
  direction: PaneResizerDirection;
  value: number;
  min: number;
  max: number;
  ariaLabel: string;
  onChange: (value: number) => void;
  onChangeEnd?: (value: number) => void;
  /**
   * Move only the separator while dragging and commit the layout once on release.
   * Useful when resizing a child (for example a virtual table) is expensive.
   */
  commitOnRelease?: boolean;
  unit?: PaneResizerUnit;
  step?: number;
  disabled?: boolean;
  controlsId?: string;
  className?: string;
}

interface DragState {
  pointerId: number;
  startCoordinate: number;
  startValue: number;
  trackSize: number;
}

export function PaneResizer({
  direction,
  value,
  min,
  max,
  ariaLabel,
  onChange,
  onChangeEnd,
  commitOnRelease = false,
  unit = 'px',
  step = unit === 'ratio' ? 0.02 : 8,
  disabled = false,
  controlsId,
  className
}: PaneResizerProps) {
  const dragRef = useRef<DragState | null>(null);
  const lastValueRef = useRef(value);
  const pendingValueRef = useRef<number | null>(null);
  const animationFrameRef = useRef<number | null>(null);
  const [dragging, setDragging] = useState(false);
  const [previewOffset, setPreviewOffset] = useState<number | null>(null);

  useEffect(() => {
    if (!dragRef.current) lastValueRef.current = value;
  }, [value]);

  useEffect(() => () => {
    dragRef.current = null;
    if (animationFrameRef.current !== null) cancelAnimationFrame(animationFrameRef.current);
  }, []);

  function handlePointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (disabled || event.button !== 0) {
      return;
    }

    const parentBounds = event.currentTarget.parentElement?.getBoundingClientRect();
    const trackSize = direction === 'horizontal'
      ? parentBounds?.width ?? 0
      : parentBounds?.height ?? 0;

    if (unit === 'ratio' && trackSize <= 0) {
      return;
    }

    event.preventDefault();
    event.currentTarget.focus();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = {
      pointerId: event.pointerId,
      startCoordinate: pointerCoordinate(event, direction),
      startValue: value,
      trackSize
    };
    lastValueRef.current = value;
    setPreviewOffset(null);
    setDragging(true);
  }

  function handlePointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }

    const pixelDelta = pointerCoordinate(event, direction) - drag.startCoordinate;
    const delta = unit === 'ratio' ? pixelDelta / drag.trackSize : pixelDelta;
    scheduleChange(normalizeValue(drag.startValue + delta, min, max, unit));
  }

  function handlePointerEnd(event: ReactPointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }

    flushScheduledChange();
    const finalValue = lastValueRef.current;
    dragRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    setPreviewOffset(null);
    setDragging(false);
    if (commitOnRelease && finalValue !== drag.startValue) onChange(finalValue);
    onChangeEnd?.(finalValue);
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (disabled) {
      return;
    }

    let nextValue: number | null = null;
    if (event.key === 'Home') {
      nextValue = min;
    } else if (event.key === 'End') {
      nextValue = max;
    } else if (
      (direction === 'horizontal' && event.key === 'ArrowLeft')
      || (direction === 'vertical' && event.key === 'ArrowUp')
    ) {
      nextValue = value - step;
    } else if (
      (direction === 'horizontal' && event.key === 'ArrowRight')
      || (direction === 'vertical' && event.key === 'ArrowDown')
    ) {
      nextValue = value + step;
    }

    if (nextValue === null) {
      return;
    }

    event.preventDefault();
    const normalizedValue = normalizeValue(nextValue, min, max, unit);
    emitChange(normalizedValue);
    onChangeEnd?.(normalizedValue);
  }

  function emitChange(nextValue: number) {
    if (nextValue === lastValueRef.current) {
      return;
    }
    lastValueRef.current = nextValue;
    onChange(nextValue);
  }

  function scheduleChange(nextValue: number) {
    pendingValueRef.current = nextValue;
    if (animationFrameRef.current !== null) return;
    animationFrameRef.current = requestAnimationFrame(() => {
      animationFrameRef.current = null;
      const pending = pendingValueRef.current;
      pendingValueRef.current = null;
      if (pending !== null) applyDragChange(pending);
    });
  }

  function flushScheduledChange() {
    if (animationFrameRef.current !== null) {
      cancelAnimationFrame(animationFrameRef.current);
      animationFrameRef.current = null;
    }
    const pending = pendingValueRef.current;
    pendingValueRef.current = null;
    if (pending !== null) applyDragChange(pending);
  }

  function applyDragChange(nextValue: number) {
    if (!commitOnRelease) {
      emitChange(nextValue);
      return;
    }

    lastValueRef.current = nextValue;
    const drag = dragRef.current;
    if (!drag) return;
    setPreviewOffset(resizerPreviewOffset(drag.startValue, nextValue, drag.trackSize, unit));
  }

  const classes = [
    'pane-resizer',
    `pane-resizer--${direction}`,
    dragging ? 'is-dragging' : '',
    commitOnRelease ? 'is-commit-on-release' : '',
    disabled ? 'is-disabled' : '',
    className ?? ''
  ].filter(Boolean).join(' ');
  const previewTransform = commitOnRelease && previewOffset !== null
    ? direction === 'horizontal'
      ? `translate3d(${previewOffset}px, 0, 0)`
      : `translate3d(0, ${previewOffset}px, 0)`
    : undefined;

  return (
    <div
      className={classes}
      role="separator"
      tabIndex={disabled ? -1 : 0}
      aria-label={ariaLabel}
      aria-controls={controlsId}
      aria-disabled={disabled || undefined}
      aria-orientation={direction === 'horizontal' ? 'vertical' : 'horizontal'}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={normalizeValue(value, min, max, unit)}
      aria-valuetext={formatValue(value, unit)}
      data-direction={direction}
      data-unit={unit}
      style={{ touchAction: 'none', transform: previewTransform }}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerEnd}
      onPointerCancel={handlePointerEnd}
      onLostPointerCapture={handlePointerEnd}
      onKeyDown={handleKeyDown}
    />
  );
}

export function resizerPreviewOffset(
  startValue: number,
  nextValue: number,
  trackSize: number,
  unit: PaneResizerUnit
): number {
  const offset = unit === 'ratio'
    ? (nextValue - startValue) * trackSize
    : nextValue - startValue;
  return Math.round(offset * 100) / 100;
}

function pointerCoordinate(
  event: ReactPointerEvent<HTMLDivElement>,
  direction: PaneResizerDirection
): number {
  return direction === 'horizontal' ? event.clientX : event.clientY;
}

function normalizeValue(value: number, min: number, max: number, unit: PaneResizerUnit): number {
  const normalized = Math.min(max, Math.max(min, value));
  return unit === 'ratio'
    ? Math.round(normalized * 10_000) / 10_000
    : Math.round(normalized);
}

function formatValue(value: number, unit: PaneResizerUnit): string {
  return unit === 'ratio'
    ? `${Math.round(value * 100)}%`
    : `${Math.round(value)} px`;
}
