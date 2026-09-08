import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { describeCommitFailure } from './commitFailure';

describe('describeCommitFailure', () => {
  // 这条就是那个 bug：错误编号是查服务端日志唯一的线索，以前被前端的文案表整句覆盖掉了。
  it('keeps the trace id the backend put in the message and in traceId', () => {
    const failure = describeCommitFailure(new ApiError(
      '服务器内部错误，请稍后重试。如需反馈请提供错误编号 a1b2c3d4。', 500,
      { code: 'INTERNAL_ERROR', traceId: 'a1b2c3d4' }
    ));
    expect(failure.message).toContain('a1b2c3d4');
    expect(failure.requestId).toBe('a1b2c3d4');
    expect(failure.copyText).toContain('错误编号：a1b2c3d4');
  });

  it('surfaces the driver text and SQLSTATE the backend passes through', () => {
    const failure = describeCommitFailure(new ApiError(
      'ORA-01407: cannot update ("X"."Y") to NULL', 400,
      { code: 'SQL_ERROR', sqlState: '23000' }
    ));
    expect(failure.title).toBe('数据库拒绝了这次修改');
    expect(failure.message).toContain('ORA-01407');
    expect(failure.sqlState).toBe('23000');
    expect(failure.copyText).toContain('SQLSTATE：23000');
  });

  it('points at the failing change when the backend reports a conflict', () => {
    const failure = describeCommitFailure(new ApiError(
      '数据已被其他操作修改或删除，本次提交已回滚。', 409,
      { code: 'DATA_EDIT_CONFLICT', changeIndex: 2, keyToken: 'token-2' }
    ));
    expect(failure.changeIndex).toBe(2);
    expect(failure.copyText).toContain('第 3 条');
  });

  it('redacts credentials that drivers sometimes put in error text', () => {
    const failure = describeCommitFailure(new ApiError('failed for password=hunter2 abc', 500, { code: 'INTERNAL_ERROR' }));
    expect(failure.message).not.toContain('hunter2');
  });

  it('falls back for plain errors and for values that are not errors at all', () => {
    expect(describeCommitFailure(new Error('boom')).code).toBe('REQUEST_FAILED');
    const unknown = describeCommitFailure('nope');
    expect(unknown.code).toBe('UNKNOWN');
    expect(unknown.message).toContain('没有返回原因');
  });
});
