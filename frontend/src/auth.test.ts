import { describe, expect, it } from 'vitest';
import { authHeaders, authenticatedUsername, isAuthenticationEnabled, isCurrentUserAdmin, setAuthStatus } from './auth';

describe('auth request state', () => {
  it('adds the server-provided csrf header only to unsafe methods', () => {
    setAuthStatus({ enabled: true, authenticated: true, username: 'operator', role: 'ADMIN', csrfToken: 'token-1', csrfHeaderName: 'X-CSRF' });

    expect(authHeaders('GET')).toEqual({});
    expect(authHeaders('POST')).toEqual({ 'X-CSRF': 'token-1' });
    expect(isAuthenticationEnabled()).toBe(true);
    expect(authenticatedUsername()).toBe('operator');
    expect(isCurrentUserAdmin()).toBe(true);
  });
});
