export type WorkbenchStage = 'connections-ready' | 'sql-editor-ready' | 'result-ready';
const PREFIX = 'mydatadev:';

/** 只保存阶段名和耗时，不记录 SQL、连接名或数据。每阶段最多保留最近 20 次。 */
export function startWorkbenchTiming(stage: WorkbenchStage) {
  const name = `${PREFIX}${stage}:start`;
  performance.clearMarks(name);
  performance.clearMarks(`${PREFIX}${stage}:response`);
  performance.mark(name);
}

export function markWorkbenchResponse(stage: WorkbenchStage) {
  const name = `${PREFIX}${stage}:response`;
  performance.clearMarks(name);
  performance.mark(name);
}

/** React 已提交后等浏览器有一次绘制机会，避免把接口完成误当成界面可用。 */
export function finishWorkbenchTimingAfterPaint(stage: WorkbenchStage): () => void {
  const name = `${PREFIX}${stage}`;
  const startName = `${name}:start`;
  const start = performance.getEntriesByName(startName, 'mark')[0];
  if (!start) return () => undefined;
  // 切回 SQL 工作台时旧结果可能先挂载，必须等本次查询的响应再结束计时。
  if (stage === 'result-ready' && !performance.getEntriesByName(`${name}:response`, 'mark').length) return () => undefined;
  let timeout = 0;
  const frame = requestAnimationFrame(() => {
    timeout = window.setTimeout(() => {
      if (performance.getEntriesByName(startName, 'mark')[0]?.startTime !== start.startTime) return;
      const previous = performance.getEntriesByName(name, 'measure').slice(-19);
      performance.clearMeasures(name);
      for (const entry of previous) performance.measure(name, { start: entry.startTime, duration: entry.duration });
      performance.measure(name, { start: start.startTime, end: performance.now() });
      performance.clearMarks(startName);
      performance.clearMarks(`${name}:response`);
    }, 0);
  });
  return () => { cancelAnimationFrame(frame); window.clearTimeout(timeout); };
}
