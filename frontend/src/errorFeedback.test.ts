import { describe, expect, it } from 'vitest';
import { describeFailure, diagnosticText, mayChangeData, redactDiagnostic } from './errorFeedback';

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
