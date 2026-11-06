import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { customDelimiterProblem, delimiterLabel, delimiterRequestFields, normalizeDelimiterSetting, scriptProblemOf } from './scriptDelimiter';

describe('script delimiter settings', () => {
  it('validates custom delimiters the same way the server does', () => {
    expect(customDelimiterProblem('!')).toBeNull();
    expect(customDelimiterProblem('$$')).toBeNull();
    expect(customDelimiterProblem('')).toContain('填写');
    expect(customDelimiterProblem(';')).toContain('分号');
    expect(customDelimiterProblem('a b')).toContain('空白');
    expect(customDelimiterProblem('--x')).toContain('注释');
    expect(customDelimiterProblem('x'.repeat(17))).toContain('16');
  });

  it('builds request fields and falls back to auto for an invalid custom token', () => {
    expect(delimiterRequestFields(undefined)).toEqual({ delimiterMode: 'AUTO' });
    expect(delimiterRequestFields({ mode: 'CUSTOM', token: ' ! ' })).toEqual({ delimiterMode: 'CUSTOM', delimiter: '!' });
    expect(delimiterRequestFields({ mode: 'CUSTOM', token: ';' })).toEqual({ delimiterMode: 'AUTO' });
    expect(delimiterRequestFields({ mode: 'BLANK_LINE' })).toEqual({ delimiterMode: 'BLANK_LINE' });
  });

  it('restores only valid settings from the saved session', () => {
    expect(normalizeDelimiterSetting({ mode: 'CUSTOM', token: '!' })).toEqual({ mode: 'CUSTOM', token: '!' });
    expect(normalizeDelimiterSetting({ mode: 'BLANK_LINE', token: 'x' })).toEqual({ mode: 'BLANK_LINE' });
    expect(normalizeDelimiterSetting({ mode: 'AUTO' })).toBeUndefined();
    expect(normalizeDelimiterSetting({ mode: 'CUSTOM', token: ';' })).toBeUndefined();
    expect(normalizeDelimiterSetting('CUSTOM')).toBeUndefined();
    expect(delimiterLabel({ mode: 'CUSTOM', token: '!' })).toBe('独占行 !');
  });

  it('recognises the three problems that need the user to choose', () => {
    const detected = new ApiError('检测到 !', 409, { code: 'SCRIPT_DELIMITER_DETECTED', delimiter: '!', statementCount: 277 });
    expect(scriptProblemOf(detected)).toMatchObject({ kind: 'delimiter-detected', delimiter: '!', statementCount: 277 });
    expect(scriptProblemOf(new ApiError('大', 409, { code: 'LARGE_SCRIPT_CONFIRMATION_REQUIRED', statementCount: 300, threshold: 200 })))
      .toMatchObject({ kind: 'large-script', statementCount: 300, threshold: 200 });
    expect(scriptProblemOf(new ApiError('太大', 400, { code: 'SCRIPT_TOO_LARGE', statementCount: 900, limit: 500 })))
      .toMatchObject({ kind: 'too-large', limit: 500 });
    expect(scriptProblemOf(new ApiError('其他', 409, { code: 'UNSCOPED_MUTATION_CONFIRMATION_REQUIRED' }))).toBeUndefined();
    expect(scriptProblemOf(new Error('x'))).toBeUndefined();
  });
});
