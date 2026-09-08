import { serializeResultExport } from '../resultExportSerialization';
import type { ResultExportMessage, ResultExportRequest } from '../types';

self.onmessage = (event: MessageEvent<ResultExportRequest>) => {
  const send = (message: ResultExportMessage) => self.postMessage(message);
  try {
    send({ stage: 'serializing' });
    send({ blob: serializeResultExport(event.data) });
  } catch (error) {
    send({ error: error instanceof Error ? error.message : '导出失败' });
  }
};
