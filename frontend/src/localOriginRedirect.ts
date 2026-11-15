/** 跳转标记只用于在目标页面显示一次提示，不保存用户数据。 */
const REDIRECT_MARKER = '__mydatadev_from_loopback=1';

/** 保留原协议、端口、路径、查询参数和锚点，只统一本机的主机名。 */
export function localhostRedirectTarget(href: string, enabled: boolean): string | undefined {
  if (!enabled) return undefined;
  const target = new URL(href);
  if ((target.protocol !== 'http:' && target.protocol !== 'https:') || target.hostname !== '127.0.0.1') return undefined;
  target.hostname = 'localhost';
  target.search += `${target.search ? '&' : '?'}${REDIRECT_MARKER}`;
  return target.href;
}

/** 只移除跳转时追加的最后一个标记，避免改动原本的查询参数及编码。 */
export function removeLocalhostRedirectMarker(href: string): string | undefined {
  const current = new URL(href);
  if (current.hostname !== 'localhost') return undefined;
  if (current.search === `?${REDIRECT_MARKER}`) {
    current.search = '';
  } else if (current.search.endsWith(`&${REDIRECT_MARKER}`)) {
    current.search = current.search.slice(0, -REDIRECT_MARKER.length - 1);
  } else {
    return undefined;
  }
  return current.href;
}
