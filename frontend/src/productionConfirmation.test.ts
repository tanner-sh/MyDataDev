import { describe, expect, it } from 'vitest';
import {
  matchesProductionConnectionName,
  normalizeProductionConfirmation,
  PRODUCTION_CONFIRMATION_HEADER,
  productionConfirmationHeaders
} from './productionConfirmation';

describe('production confirmation', () => {
  it('去除输入首尾空格后匹配生产连接名', () => {
    expect(normalizeProductionConfirmation('  yaser@upload-notify\n')).toBe('yaser@upload-notify');
    expect(matchesProductionConnectionName(' yaser@upload-notify ', 'yaser@upload-notify')).toBe(true);
  });

  it('连接名不一致时拒绝确认', () => {
    expect(matchesProductionConnectionName('yaser@other-db', 'yaser@upload-notify')).toBe(false);
  });
});

describe('productionConfirmationHeaders', () => {
  it('没有需要确认的内容时不发这个头', () => {
    expect(productionConfirmationHeaders()).toEqual({});
    expect(productionConfirmationHeaders('')).toEqual({});
    expect(productionConfirmationHeaders(null)).toEqual({});
  });

  it('编码中文连接名，否则 fetch 根本发不出请求', () => {
    expect(productionConfirmationHeaders('生产订单库')[PRODUCTION_CONFIRMATION_HEADER])
      .toBe('%E7%94%9F%E4%BA%A7%E8%AE%A2%E5%8D%95%E5%BA%93');
  });

  it('产出的头值一定落在 ISO-8859-1 内，且能无损还原', () => {
    for (const name of ['生产订单库', 'prod🚀', '折扣100%库', 'café', 'prod+backup']) {
      const value = productionConfirmationHeaders(name)[PRODUCTION_CONFIRMATION_HEADER];
      // 浏览器构造 Headers 时的实际约束：码点必须 <= 0xFF。
      expect([...value].every((character) => character.charCodeAt(0) <= 0xff)).toBe(true);
      expect(decodeURIComponent(value)).toBe(name);
    }
  });

  it('纯 ASCII 名字编码后不变，已有连接不受影响', () => {
    expect(productionConfirmationHeaders('prod-main')[PRODUCTION_CONFIRMATION_HEADER]).toBe('prod-main');
  });

  it('真的能被 Headers 构造函数接受', () => {
    expect(() => new Headers(productionConfirmationHeaders('生产订单库'))).not.toThrow();
    // 对照组：不编码就是今天线上的行为。
    expect(() => new Headers({ [PRODUCTION_CONFIRMATION_HEADER]: '生产订单库' })).toThrow();
  });
});
