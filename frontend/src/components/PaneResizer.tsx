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
  unit = 'px',
  step = unit === 'ratio' ? 0.02 : 8,
  disabled = false,
  controlsId,
  className
}: PaneResizerProps) {
  const dragRef = useRef<DragState | null>(null);
  const lastValueRef = useRef(value);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    lastValueRef.current = value;
  }, [value]);

  useEffect(() => () => {
    dragRef.current = null;
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
    setDragging(true);
  }

  function handlePointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }

    const pixelDelta = pointerCoordinate(event, direction) - drag.startCoordinate;
    const delta = unit === 'ratio' ? pixelDelta / drag.trackSize : pixelDelta;
    emitChange(normalizeValue(drag.startValue + delta, min, max, unit));
  }

  function handlePointerEnd(event: ReactPointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }

    dragRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    setDragging(false);
    onChangeEnd?.(lastValueRef.current);
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

  const classes = [
    'pane-resizer',
    `pane-resizer--${direction}`,
    dragging ? 'is-dragging' : '',
    disabled ? 'is-disabled' : '',
    className ?? ''
  ].filter(Boolean).join(' ');

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
      style={{ touchAction: 'none' }}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerEnd}
      onPointerCancel={handlePointerEnd}
      onLostPointerCapture={handlePointerEnd}
      onKeyDown={handleKeyDown}
    />
  );
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
