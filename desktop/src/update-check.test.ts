import { describe, expect, it } from 'vitest';
import { compareVersions, parseLatestRelease, shouldNotify } from './update-check.js';

describe('compareVersions', () => {
  /** 字符串比较会说 0.9.0 比 0.10.0 新，这是这件事里唯一容易出错的地方。 */
  it('按数字比较每一段，而不是按字符串', () => {
    expect(compareVersions('0.10.0', '0.9.0')).toBeGreaterThan(0);
    expect(compareVersions('1.0.0', '0.99.99')).toBeGreaterThan(0);
    expect(compareVersions('0.5.0', '0.5.0')).toBe(0);
    expect(compareVersions('0.5.1', '0.5.2')).toBeLessThan(0);
  });

  it('容忍 v 前缀与预发布标记', () => {
    expect(compareVersions('v0.6.0', '0.6.0')).toBe(0);
    expect(compareVersions('0.6.0-rc.1', '0.6.0')).toBe(0);
  });

  it('缺段按 0 处理', () => {
    expect(compareVersions('1', '1.0.0')).toBe(0);
    expect(compareVersions('1.2', '1.2.1')).toBeLessThan(0);
  });
});

describe('parseLatestRelease', () => {
  it('取出版本号与页面地址', () => {
    expect(parseLatestRelease({ tag_name: 'v0.6.0', html_url: 'https://example/releases/v0.6.0' }))
      .toEqual({ version: '0.6.0', url: 'https://example/releases/v0.6.0' });
  });

  /** 草稿和预发布不该弹给普通用户。 */
  it('跳过草稿与预发布', () => {
    expect(parseLatestRelease({ tag_name: 'v9.9.9', html_url: 'x', draft: true })).toBeNull();
    expect(parseLatestRelease({ tag_name: 'v9.9.9', html_url: 'x', prerelease: true })).toBeNull();
  });

  it('形状不对时返回 null 而不是抛错', () => {
    expect(parseLatestRelease(null)).toBeNull();
    expect(parseLatestRelease({})).toBeNull();
    expect(parseLatestRelease({ tag_name: 'nightly' })).toBeNull();
    expect(parseLatestRelease('不是对象')).toBeNull();
  });
});

describe('shouldNotify', () => {
  const latest = { version: '0.6.0', url: 'https://example/releases/v0.6.0' };

  it('只有确实更新时才提示', () => {
    expect(shouldNotify('0.5.0', latest)).toBe(true);
    expect(shouldNotify('0.6.0', latest)).toBe(false);
    expect(shouldNotify('0.7.0', latest)).toBe(false);
  });

  /** 与其弹一个点开发现没有的提示，不如什么都不做。 */
  it('拿不到版本或地址时不提示', () => {
    expect(shouldNotify('0.5.0', null)).toBe(false);
    expect(shouldNotify('0.5.0', { version: '0.6.0', url: '' })).toBe(false);
  });

  it('开发模式下不提示', () => {
    expect(shouldNotify('0.5.0', latest, true)).toBe(false);
  });
});
