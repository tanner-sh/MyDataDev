import { API } from './constants';
import { mayChangeData, reportFailure } from './errorFeedback';
import { AUTH_REQUIRED_EVENT, isSessionAuthenticated, markSessionExpired, authHeaders } from './auth';

export class ApiError extends Error {
  code?: string;
  confirmationText?: string;
  statements?: Array<{ index: number; sql: string }>;
  status: number;
  requestId?: string;
  details: Record<string, unknown>;

  constructor(message: string, status: number, payload?: Record<string, unknown>) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.details = payload || {};
    this.requestId = typeof payload?.requestId === 'string' ? payload.requestId : typeof payload?.traceId === 'string' ? payload.traceId : undefined;
    this.code = typeof payload?.code === 'string' ? payload.code : undefined;
    this.confirmationText = typeof payload?.confirmationText === 'string' ? payload.confirmationText : undefined;
    this.statements = Array.isArray(payload?.statements)
      ? payload.statements.flatMap((item) => {
          if (!item || typeof item !== 'object') return [];
          const statement = item as Record<string, unknown>;
          return typeof statement.index === 'number' && typeof statement.sql === 'string'
            ? [{ index: statement.index, sql: statement.sql }]
            : [];
        })
      : undefined;
  }
}

/** 取出后端问题详情里的 code；不是 ApiError（网络中断、解析失败）时返回 undefined。 */
export function apiErrorCode(error: unknown): string | undefined {
  return error instanceof ApiError ? error.code : undefined;
}

/**
 * 发起带 Session、CSRF 与统一错误处理的 API 请求，并保留原始 Response 给文件下载等场景。
 */
export async function apiResponse(path: string, init?: RequestInit): Promise<Response> {
  const isFormData = init?.body instanceof FormData;
  if (!isSessionAuthenticated() && !path.startsWith('/auth/')) throw new ApiError('登录已过期，请重新登录后继续。', 401, { code: 'AUTH_REQUIRED' });
  let res: Response;
  try {
    res = await fetch(`${API}${path}`, {
    ...init,
    signal: init?.signal || (typeof AbortSignal.timeout === 'function' ? AbortSignal.timeout(['GET', 'HEAD'].includes(init?.method || 'GET') ? 30_000 : 180_000) : undefined),
    credentials: 'include',
    headers: { ...(!isFormData ? { 'Content-Type': 'application/json' } : {}), 'X-User': 'admin', ...authHeaders(init?.method), ...(init?.headers || {}) }
    });
  } catch (cause) {
    if (cause instanceof Error && cause.name === 'AbortError') throw cause;
    const unknown = mayChangeData(path, init?.method);
    const error = new ApiError(unknown ? '请求已发出，但未收到完整响应，操作结果待确认。' : '无法连接服务器，请检查网络与后端服务。', 0,
      { code: unknown ? 'OPERATION_RESULT_UNKNOWN' : cause instanceof Error && cause.name === 'TimeoutError' ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR' });
    reportFailure(error, path);
    throw error;
  }
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith('/auth/')) {
      markSessionExpired();
      if (typeof window !== 'undefined') window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT));
    }
    const responseText = await res.text().catch(() => '服务器错误响应不完整，请结合请求编号排查。');
    let err: Record<string, unknown>;
    try {
      const parsed = JSON.parse(responseText) as unknown;
      err = parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : { message: responseText };
    } catch {
      err = { message: responseText.trim() || res.statusText };
    }
    const error = new ApiError(typeof err.message === 'string' ? err.message : res.statusText, res.status, { ...err, requestId: res.headers?.get('X-Request-ID') || err.traceId });
    reportFailure(error, path);
    throw error;
  }
  return res;
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await apiResponse(path, init);
  if (res.status === 204) return undefined as T;
  // 一些写接口成功返回空响应；只有存在响应体却无法解析时才是结果不确定。
  try {
    const text = await res.text();
    return text.trim() ? JSON.parse(text) as T : undefined as T;
  } catch {
    const error = new ApiError(mayChangeData(path, init?.method) ? '操作响应不完整，结果待确认，请先核对目标数据。' : '服务器返回了不完整的数据，请重新加载。', res.status,
      { code: mayChangeData(path, init?.method) ? 'OPERATION_RESULT_UNKNOWN' : 'INVALID_RESPONSE', requestId: res.headers?.get('X-Request-ID') });
    reportFailure(error, path);
    throw error;
  }
}

export function uploadBinary<T>(path: string, file: File, onProgress: (percent: number) => void, signal?: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open('POST', `${API}${path}`);
    request.responseType = 'json';
    request.withCredentials = true;
    request.setRequestHeader('Content-Type', 'application/octet-stream');
    request.setRequestHeader('X-User', 'admin');
    Object.entries(authHeaders('POST')).forEach(([name, value]) => request.setRequestHeader(name, value));
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
        if (request.status === 401 && typeof window !== 'undefined') window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT));
        const error = new ApiError(typeof payload.message === 'string' ? payload.message : request.statusText, request.status, payload);
        reportFailure(error, path);
        reject(error);
      }
    };
    request.onerror = () => reject(new ApiError('文件上传失败，请检查网络连接', request.status || 0));
    request.onabort = () => reject(new DOMException('SQL 文件上传已取消', 'AbortError'));
    if (signal) {
      if (signal.aborted) { reject(new DOMException('上传已取消', 'AbortError')); return; }
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
