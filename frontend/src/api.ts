import { API } from './constants';

export class ApiError extends Error {
  code?: string;
  confirmationText?: string;
  status: number;

  constructor(message: string, status: number, payload?: Record<string, unknown>) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = typeof payload?.code === 'string' ? payload.code : undefined;
    this.confirmationText = typeof payload?.confirmationText === 'string' ? payload.confirmationText : undefined;
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const isFormData = init?.body instanceof FormData;
  const res = await fetch(`${API}${path}`, {
    ...init,
    headers: { ...(!isFormData ? { 'Content-Type': 'application/json' } : {}), 'X-User': 'admin', ...(init?.headers || {}) }
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText })) as Record<string, unknown>;
    throw new ApiError(typeof err.message === 'string' ? err.message : res.statusText, res.status, err);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Safari/Firefox may not have consumed the object URL synchronously when
  // click() returns.
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
}

export function downloadFromUrl(url: string) {
  const link = document.createElement('a');
  link.href = url;
  link.download = '';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
}
