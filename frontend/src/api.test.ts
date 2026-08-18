import { describe, expect, it, vi } from 'vitest';
import { api } from './api';

describe('api error handling', () => {
  it('keeps a plain-text server error so the UI can explain the failure', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      status: 403,
      statusText: 'Forbidden',
      text: async () => 'Invalid CORS request'
    } as Response);

    await expect(api('/connections/test', { method: 'POST', body: '{}' }))
      .rejects.toMatchObject({ status: 403, message: 'Invalid CORS request' });

    fetchMock.mockRestore();
  });
});
