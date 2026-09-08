import { describe, expect, it, vi } from 'vitest';
import { exportResult, shouldUseExportWorker } from './resultExport';
import { serializeResultExport } from './resultExportSerialization';
import { serializeQueryResult } from './queryResultExport';
import { buildXlsx } from './xlsx';
import type { ExportFormat, ResultExportMessage, ResultExportRequest } from './types';

const small: ResultExportRequest = {
  format: 'csv', columns: [{ key: 'id', label: 'ID', typeName: 'BIGINT' }, { key: 'note', label: '备注', typeName: 'VARCHAR' }],
  rows: [['1234567890123456789', '中文,换行\n<&>'], [null, '']], options: { dbType: 'h2', targetTableParts: ['PUBLIC', 'T'] }
};
const large = { ...small, rows: Array.from({ length: 5000 }, () => small.rows[0]) };
function fakeWorker() {
  const worker = { postMessage: vi.fn(), terminate: vi.fn(), onmessage: null as Worker['onmessage'], onerror: null as Worker['onerror'], onmessageerror: null as Worker['onmessageerror'] };
  return { worker, send(data: ResultExportMessage) { worker.onmessage?.call(worker as unknown as Worker, { data } as MessageEvent); } };
}

describe('结果导出', () => {
  it('大单元格数量或长文本进入 Worker，小结果直接序列化', async () => {
    expect(shouldUseExportWorker(small)).toBe(false);
    expect(shouldUseExportWorker(large)).toBe(true);
    expect(shouldUseExportWorker({ ...small, rows: [['x'.repeat(256_000)]] })).toBe(true);
    const factory = vi.fn();
    expect(await (await exportResult(small, { createWorker: factory })).text()).toBe(await serializeResultExport(small).text());
    expect(factory).not.toHaveBeenCalled();
  });
  it.each<ExportFormat>(['csv', 'json', 'sql', 'xml', 'markdown', 'xlsx'])('迁移后 %s 文件保持原有格式、NULL 和精度规则', async (format) => {
    const request = { ...small, format };
    const blob = serializeResultExport(request);
    if (format === 'xlsx') expect(await blob.arrayBuffer()).toEqual(await buildXlsx(small.columns, small.rows).arrayBuffer());
    else expect(new Uint8Array(await blob.arrayBuffer())).toEqual(new TextEncoder().encode(serializeQueryResult(format, small.columns, small.rows, small.options)));
  });
  it('传递原始快照、报告生成状态，完成后释放 Worker', async () => {
    const { worker, send } = fakeWorker();
    const stage = vi.fn();
    const pending = exportResult(large, { createWorker: () => worker, onStage: stage });
    expect(worker.postMessage).toHaveBeenCalledWith(large);
    send({ stage: 'serializing' });
    const blob = new Blob(['export']);
    send({ blob });
    expect(await pending).toBe(blob);
    expect(stage.mock.calls.flat()).toEqual(['preparing', 'serializing']);
    expect(worker.terminate).toHaveBeenCalledOnce();
    expect(worker.onmessage).toBeNull();
  });
  it('取消时终止计算并拒绝下载，已取消的任务不会创建 Worker', async () => {
    const { worker } = fakeWorker();
    const controller = new AbortController();
    const pending = exportResult(large, { createWorker: () => worker, signal: controller.signal });
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(worker.terminate).toHaveBeenCalledOnce();
    const factory = vi.fn();
    await expect(exportResult(large, { createWorker: factory, signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
    expect(factory).not.toHaveBeenCalled();
  });
  it('序列化和数据传递失败时释放 Worker 并反馈错误', async () => {
    const first = fakeWorker();
    const pending = exportResult(large, { createWorker: () => first.worker });
    first.send({ error: '无法序列化' });
    await expect(pending).rejects.toThrow('无法序列化');
    expect(first.worker.terminate).toHaveBeenCalledOnce();
    const second = fakeWorker();
    second.worker.postMessage.mockImplementation(() => { throw new Error('复制失败'); });
    await expect(exportResult(large, { createWorker: () => second.worker })).rejects.toThrow('复制失败');
    expect(second.worker.terminate).toHaveBeenCalledOnce();
  });
});
