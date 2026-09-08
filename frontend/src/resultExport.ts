import type { ResultExportMessage, ResultExportRequest, ResultExportStage } from './types';

export function shouldUseExportWorker(request: ResultExportRequest): boolean {
  if (request.rows.length * request.columns.length >= 10_000) return true;
  let characters = 0;
  for (const row of request.rows) {
    for (const value of row) {
      if (typeof value === 'string') characters += value.length;
      if (characters >= 256_000) return true;
    }
  }
  return false;
}

type ExportWorker = Pick<Worker, 'postMessage' | 'terminate' | 'onmessage' | 'onerror' | 'onmessageerror'>;
type ExportOptions = {
  signal?: AbortSignal;
  onStage?: (stage: ResultExportStage) => void;
  createWorker?: () => ExportWorker;
};

export async function exportResult(request: ResultExportRequest, { signal, onStage, createWorker = () =>
  new Worker(new URL('./workers/resultExportWorker.ts', import.meta.url), { type: 'module' })
}: ExportOptions = {}): Promise<Blob> {
  const aborted = () => new DOMException('已取消导出', 'AbortError');
  if (signal?.aborted) throw aborted();
  onStage?.('preparing');
  if (!shouldUseExportWorker(request)) {
    const { serializeResultExport } = await import('./resultExportSerialization');
    if (signal?.aborted) throw aborted();
    onStage?.('serializing');
    return serializeResultExport(request);
  }
  return new Promise<Blob>((resolve, reject) => {
    const worker = createWorker();
    const finish = (error?: Error, blob?: Blob) => {
      signal?.removeEventListener('abort', cancel);
      worker.onmessage = worker.onerror = worker.onmessageerror = null;
      worker.terminate();
      if (error) reject(error);
      else if (blob) resolve(blob);
    };
    const cancel = () => finish(aborted());
    signal?.addEventListener('abort', cancel, { once: true });
    worker.onmessage = (event: MessageEvent<ResultExportMessage>) => {
      const response = event.data;
      if ('stage' in response) onStage?.(response.stage);
      else if ('blob' in response) finish(undefined, response.blob);
      else finish(new Error(response.error));
    };
    worker.onerror = () => finish(new Error('导出处理失败，请重试或使用重新查询并导出'));
    worker.onmessageerror = () => finish(new Error('无法读取导出文件，请重试'));
    try {
      if (signal?.aborted) cancel();
      else worker.postMessage(request);
    } catch (error) {
      finish(error instanceof Error ? error : new Error('无法准备导出数据'));
    }
  });
}
