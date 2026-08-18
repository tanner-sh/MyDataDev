import { describe, expect, it } from 'vitest';
import { matchesProductionConnectionName, normalizeProductionConfirmation } from './productionConfirmation';

describe('production confirmation', () => {
  it('去除输入首尾空格后匹配生产连接名', () => {
    expect(normalizeProductionConfirmation('  yaser@upload-notify\n')).toBe('yaser@upload-notify');
    expect(matchesProductionConnectionName(' yaser@upload-notify ', 'yaser@upload-notify')).toBe(true);
  });

  it('连接名不一致时拒绝确认', () => {
    expect(matchesProductionConnectionName('yaser@other-db', 'yaser@upload-notify')).toBe(false);
  });
});
