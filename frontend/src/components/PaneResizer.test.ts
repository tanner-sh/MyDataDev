import { describe, expect, it } from 'vitest';
import { resizerPreviewOffset } from './PaneResizer';

describe('PaneResizer preview offset', () => {
  it('converts a ratio change into pixels without changing the layout', () => {
    expect(resizerPreviewOffset(0.4, 0.65, 800, 'ratio')).toBe(200);
  });

  it('uses the pixel delta directly for pixel-based resizers', () => {
    expect(resizerPreviewOffset(300, 252, 1_000, 'px')).toBe(-48);
  });

  it('keeps fractional ratio previews stable', () => {
    expect(resizerPreviewOffset(0.52, 0.5375, 731, 'ratio')).toBe(12.79);
  });
});
