import { describe, expect, it } from 'vitest';
import { localhostRedirectTarget, removeLocalhostRedirectMarker } from './localOriginRedirect';

describe('Web 本机地址统一', () => {
  it('只替换 127.0.0.1，并在目标页面移除提示标记', () => {
    const original = 'http://127.0.0.1:8080/workbench?q=a%20b&value=%2F#editor';
    const target = localhostRedirectTarget(original, true);

    expect(target).toBe('http://localhost:8080/workbench?q=a%20b&value=%2F&__mydatadev_from_loopback=1#editor');
    expect(removeLocalhostRedirectMarker(target!)).toBe('http://localhost:8080/workbench?q=a%20b&value=%2F#editor');
  });

  it('支持无参数地址，并保留用户已有的同名参数', () => {
    const target = localhostRedirectTarget('http://127.0.0.1:5173/#sql', true);
    expect(target).toBe('http://localhost:5173/?__mydatadev_from_loopback=1#sql');
    expect(removeLocalhostRedirectMarker(target!)).toBe('http://localhost:5173/#sql');

    const existing = localhostRedirectTarget('https://127.0.0.1/?__mydatadev_from_loopback=1', true);
    expect(removeLocalhostRedirectMarker(existing!)).toBe('https://localhost/?__mydatadev_from_loopback=1');
  });

  it('桌面模式、其他域名和非 HTTP 地址不触发跳转', () => {
    expect(localhostRedirectTarget('http://127.0.0.1:5173/', false)).toBeUndefined();
    expect(localhostRedirectTarget('http://localhost:8080/', true)).toBeUndefined();
    expect(localhostRedirectTarget('http://192.168.1.8:8080/', true)).toBeUndefined();
    expect(localhostRedirectTarget('https://db.example.com/', true)).toBeUndefined();
    expect(localhostRedirectTarget('ftp://127.0.0.1/data', true)).toBeUndefined();
    expect(removeLocalhostRedirectMarker('http://127.0.0.1/?__mydatadev_from_loopback=1')).toBeUndefined();
  });
});
