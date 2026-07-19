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

export function uploadBinary<T>(path: string, file: File, onProgress: (percent: number) => void, signal?: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open('POST', `${API}${path}`);
    request.responseType = 'json';
    request.setRequestHeader('Content-Type', 'application/octet-stream');
    request.setRequestHeader('X-User', 'admin');
    request.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) onProgress(Math.round(event.loaded * 100 / event.total));
    };
    request.onload = () => {
      const payload = request.response && typeof request.response === 'object'
        ? request.response as Record<string, unknown>
        : {};
      if (request.status >= 200 && request.status < 300) {
        onProgress(100);
        resolve(payload as T);
      } else {
        reject(new ApiError(typeof payload.message === 'string' ? payload.message : request.statusText, request.status, payload));
      }
    };
    request.onerror = () => reject(new ApiError('SQL 文件上传失败，请检查网络连接', request.status || 0));
    request.onabort = () => reject(new DOMException('SQL 文件上传已取消', 'AbortError'));
    if (signal) {
      if (signal.aborted) request.abort();
      else signal.addEventListener('abort', () => request.abort(), { once: true });
    }
    request.send(file);
  });
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
