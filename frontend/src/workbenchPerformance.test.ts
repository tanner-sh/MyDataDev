import { afterEach, describe, expect, it, vi } from 'vitest';
import { finishWorkbenchTimingAfterPaint, markWorkbenchResponse, startWorkbenchTiming } from './workbenchPerformance';

afterEach(() => {
  performance.clearMarks(); performance.clearMeasures();
  vi.unstubAllGlobals(); vi.useRealTimers();
});
function browserTimers() {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  vi.stubGlobal('window', globalThis);
  vi.stubGlobal('requestAnimationFrame', (callback: () => void) => setTimeout(callback, 16));
  vi.stubGlobal('cancelAnimationFrame', clearTimeout);
}
describe('工作台阶段耗时', () => {
  it('等待一次绘制机会后记录阶段，不记录重复完成或业务内容', () => {
    browserTimers();
    startWorkbenchTiming('result-ready');
    markWorkbenchResponse('result-ready');
    finishWorkbenchTimingAfterPaint('result-ready');
    expect(performance.getEntriesByType('measure')).toHaveLength(0);
    vi.runAllTimers();
    const entries = performance.getEntriesByName('mydatadev:result-ready');
    expect(entries).toHaveLength(1);
    expect(entries[0].duration).toBeGreaterThanOrEqual(0);
    finishWorkbenchTimingAfterPaint('result-ready'); vi.runAllTimers();
    expect(performance.getEntriesByType('measure')).toHaveLength(1);
  });
  it('组件卸载时取消待记录阶段，历史数量有界', () => {
    browserTimers();
    startWorkbenchTiming('sql-editor-ready');
    finishWorkbenchTimingAfterPaint('sql-editor-ready')(); vi.runAllTimers();
    expect(performance.getEntriesByType('measure')).toHaveLength(0);
    for (let i = 0; i < 30; i++) {
      startWorkbenchTiming('connections-ready');
      finishWorkbenchTimingAfterPaint('connections-ready'); vi.runAllTimers();
    }
    expect(performance.getEntriesByName('mydatadev:connections-ready')).toHaveLength(20);
  });
  it('旧结果重新挂载不能提前完成新查询的计时', () => {
    browserTimers();
    startWorkbenchTiming('result-ready');
    finishWorkbenchTimingAfterPaint('result-ready'); vi.runAllTimers();
    expect(performance.getEntriesByType('measure')).toHaveLength(0);
    markWorkbenchResponse('result-ready');
    startWorkbenchTiming('result-ready'); // 再次执行时旧响应标记也应失效。
    finishWorkbenchTimingAfterPaint('result-ready'); vi.runAllTimers();
    expect(performance.getEntriesByType('measure')).toHaveLength(0);
    markWorkbenchResponse('result-ready');
    finishWorkbenchTimingAfterPaint('result-ready'); vi.runAllTimers();
    expect(performance.getEntriesByType('measure')).toHaveLength(1);
  });
});
