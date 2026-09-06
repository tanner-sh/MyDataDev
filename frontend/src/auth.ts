import { API } from './constants';

export const AUTH_LOGOUT_EVENT = 'mydatadev:logout';
export const PERSIST_WORK_EVENT = 'mydatadev:persist-work';
export const AUTH_REQUIRED_EVENT = 'mydatadev:auth-required';

export type AuthStatus = {
  enabled: boolean;
  authenticated: boolean;
  username?: string | null;
  displayName?: string | null;
  role?: 'ADMIN' | 'OPERATOR' | null;
  provider?: string | null;
  passwordLogin?: boolean;
  loginUrl?: string | null;
  csrfToken?: string | null;
  csrfHeaderName?: string | null;
};

let authRequestGeneration = 0;
let currentStatus: AuthStatus = { enabled: false, authenticated: true };

export function setAuthStatus(status: AuthStatus) {
  currentStatus = status;
}

export function workspaceIdentity() {
  return currentStatus.enabled ? `user:${currentStatus.provider || 'local'}:${currentStatus.username || 'anonymous'}` : 'local';
}

export function markSessionExpired() { currentStatus = { ...currentStatus, authenticated: false }; }

export function isSessionAuthenticated() { return !currentStatus.enabled || currentStatus.authenticated; }

export function isAuthenticationEnabled() {
  return currentStatus.enabled;
}

export function authenticatedUsername() {
  return currentStatus.username || '';
}

export function authenticatedDisplayName() {
  return currentStatus.displayName || currentStatus.username || '';
}

export function isCurrentUserAdmin() {
  return currentStatus.enabled && currentStatus.authenticated && currentStatus.role === 'ADMIN';
}

export function authHeaders(method = 'GET'): Record<string, string> {
  if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method.toUpperCase())
      && currentStatus.csrfToken && currentStatus.csrfHeaderName) {
    return { [currentStatus.csrfHeaderName]: currentStatus.csrfToken };
  }
  return {};
}

async function parse<T>(response: Response): Promise<T> {
  const payload = await response.json().catch(() => ({})) as Record<string, unknown>;
  if (!response.ok) {
    throw new Error(typeof payload.message === 'string' ? payload.message : response.statusText);
  }
  return payload as T;
}

export async function loadAuthStatus(): Promise<AuthStatus> {
  const generation = ++authRequestGeneration;
  const status = await parse<AuthStatus>(await fetch(`${API}/auth/status`, { credentials: 'include', signal: AbortSignal.timeout(30_000) }));
  if (generation === authRequestGeneration) setAuthStatus(status);
  return status;
}

export async function login(username: string, password: string): Promise<AuthStatus> {
  const generation = ++authRequestGeneration;
  const response = await fetch(`${API}/auth/login`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...authHeaders('POST') },
    body: JSON.stringify({ username, password })
  });
  const status = await parse<AuthStatus>(response);
  if (generation === authRequestGeneration) setAuthStatus(status);
  return status;
}

export async function logout(): Promise<void> {
  await fetch(`${API}/auth/logout`, {
    method: 'POST',
    credentials: 'include',
    headers: authHeaders('POST')
  });
  window.dispatchEvent(new Event(PERSIST_WORK_EVENT));
  currentStatus = { ...currentStatus, authenticated: false, username: null };
  window.dispatchEvent(new Event(AUTH_LOGOUT_EVENT));
  window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT));
}
