import { describe, expect, it } from 'vitest';
import { describeFailure, diagnosticText, isPromptCode, mayChangeData, redactDiagnostic } from './errorFeedback';

describe('actionable error feedback', () => {
  it('redacts credentials and omits query parameters in copied diagnostics', () => {
    const text = diagnosticText(describeFailure({ message: 'jdbc:mysql://alice:private@db/test?password=hidden&token=abc', requestId: 'trace-7' }, '/connections?secret=xyz'));
    expect(text).not.toMatch(/private|hidden|abc|xyz/);
    expect(text).toContain('trace-7');
    expect(redactDiagnostic('pwd=secret; API-key=abcdef')).not.toMatch(/secret|abcdef/);
  });
  it('tells users to verify the outcome of interrupted writes', () => {
    expect(describeFailure({ message: '响应中断', code: 'OPERATION_RESULT_UNKNOWN' }).advice).toContain('先查看 SQL 历史');
    expect(mayChangeData('/data/commit', 'POST')).toBe(true);
    expect(mayChangeData('/sql/execute', 'POST')).toBe(true);
    expect(mayChangeData('/connections/1/test', 'POST')).toBe(false);
    expect(mayChangeData('/data/preview', 'POST')).toBe(false);
    expect(mayChangeData('/connections', 'GET')).toBe(false);
  });
});

describe('prompts are not failures', () => {
  it('keeps the confirmation prompts out of the problem center', () => {
    for (const code of ['SCRIPT_DELIMITER_DETECTED', 'LARGE_SCRIPT_CONFIRMATION_REQUIRED', 'SCRIPT_TOO_LARGE', 'UNSCOPED_MUTATION_CONFIRMATION_REQUIRED', 'PRODUCTION_CONFIRMATION_REQUIRED']) {
      expect(isPromptCode(code)).toBe(true);
    }
    expect(isPromptCode('SQL_FAILED')).toBe(false);
    expect(isPromptCode(undefined)).toBe(false);
  });
});
