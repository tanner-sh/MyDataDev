import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, apiResponse } from './api';
import { setAuthStatus } from './auth';

afterEach(() => {
  vi.restoreAllMocks();
  setAuthStatus({ enabled: false, authenticated: true });
});

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

  });

  it('adds session credentials and csrf header to authenticated downloads', async () => {
    setAuthStatus({
      enabled: true,
      authenticated: true,
      username: 'alice',
      role: 'OPERATOR',
      csrfToken: 'csrf-123',
      csrfHeaderName: 'X-CSRF-TOKEN'
    });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      status: 200
    } as Response);

    await apiResponse('/sql/export', {
      method: 'POST',
      headers: { 'X-Production-Confirmation': 'prod' },
      body: '{}'
    });

    expect(fetchMock).toHaveBeenCalledWith('/api/sql/export', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      headers: expect.objectContaining({
        'Content-Type': 'application/json',
        'X-User': 'admin',
        'X-CSRF-TOKEN': 'csrf-123',
        'X-Production-Confirmation': 'prod'
      })
    }));
  });
});
