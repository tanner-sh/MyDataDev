import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { backgroundSubmitFailure, describeRoutineInvokeFailure, routineInvokePath } from './routineInvoke';
import type { SqlFileExecution } from './types';

describe('routineInvokePath', () => {
  it('routes foreground and background invocations to their endpoints', () => {
    expect(routineInvokePath(7, 'foreground')).toBe('/metadata/7/schema-objects/invoke');
    expect(routineInvokePath(7, 'background')).toBe('/metadata/7/schema-objects/invoke-background');
  });
});

describe('describeRoutineInvokeFailure', () => {
  it('suggests background execution only after a timeout', () => {
    const timeout = describeRoutineInvokeFailure(new ApiError('调用超过前台调用的上限 1 分钟', 400, { code: 'SQL_TIMEOUT' }));
    expect(timeout).toMatchObject({ kind: 'timeout', suggestBackground: true, message: '调用超过前台调用的上限 1 分钟' });

    const failure = describeRoutineInvokeFailure(new ApiError('ORA-06550', 400, { code: 'SQL_ERROR' }));
    expect(failure).toMatchObject({ kind: 'error', suggestBackground: false });
  });

  it('reports a user cancel as a cancel, not a failure', () => {
    expect(describeRoutineInvokeFailure(new ApiError('调用已取消', 409, { code: 'SQL_CANCELLED' }))).toMatchObject({ kind: 'cancelled', title: '调用已取消' });
  });

  it('warns not to retry when the outcome is unknown', () => {
    const unknown = describeRoutineInvokeFailure(new ApiError('请求已发出，但未收到完整响应，操作结果待确认。', 0, { code: 'OPERATION_RESULT_UNKNOWN' }));
    expect(unknown?.message).toContain('不要直接重试');
  });

  it('stays quiet when the page aborted the request itself', () => {
    const aborted = new Error('aborted');
    aborted.name = 'AbortError';
    expect(describeRoutineInvokeFailure(aborted)).toBeUndefined();
  });
});

describe('backgroundSubmitFailure', () => {
  it('only reports jobs that never started', () => {
    expect(backgroundSubmitFailure({ status: 'FAILED', message: '调用未开始执行：队列已满' } as SqlFileExecution)?.message).toBe('调用未开始执行：队列已满');
    expect(backgroundSubmitFailure({ status: 'QUEUED' } as SqlFileExecution)).toBeUndefined();
  });
});
