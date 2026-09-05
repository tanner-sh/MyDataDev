import type { ConnectionPoolOverview, ConnectionPoolStatus } from './types';

/**
 * 远程连接池的解读。
 *
 * 池此前只在出问题的那一刻才「被看见」：REMOTE_POOL_EXHAUSTED 说「已达到上限（20 个）」，
 * 却说不出名额被谁占着、哪个早就闲了。这些判断是纯规则，放在组件外面。
 */

export type PoolPressure = {
  used: number;
  capacity: number;
  free: number;
  level: 'ok' | 'tight' | 'full';
  hint: string;
};

/** 名额紧张的阈值：还剩不到两成时提前说，等到 0 才提示已经晚了。 */
export function poolPressure(overview: ConnectionPoolOverview): PoolPressure {
  const capacity = Math.max(1, overview.capacity);
  const used = overview.pools.length;
  const free = Math.max(0, capacity - used);
  if (free === 0) {
    return { used, capacity, free, level: 'full', hint: '名额已用满：新连接要等一个空闲的池被淘汰后才建得起来。' };
  }
  if (free <= Math.max(1, Math.floor(capacity * 0.2))) {
    return { used, capacity, free, level: 'tight', hint: `只剩 ${free} 个名额，闲置的池会被自动淘汰让位。` };
  }
  return { used, capacity, free, level: 'ok', hint: `还有 ${free} 个名额。` };
}

/** 值得说出来的异常。正常的池不该有任何提示 —— 全都亮着等于什么都没说。 */
export function poolWarnings(pool: ConnectionPoolStatus): string[] {
  const warnings: string[] = [];
  if (pool.waiting > 0) {
    warnings.push(`有 ${pool.waiting} 个请求在排队等连接：每条连接最多 ${pool.maxPoolSize} 条物理连接，已经不够用了。`);
  }
  if (pool.tunnelAlive === false) {
    warnings.push('SSH 隧道已断开，池里的连接都已失效；下一次请求会重新建隧道和池。');
  }
  return warnings;
}

/** 空闲时长。名额不够时被淘汰的就是空闲最久且没人用的那个，所以这个数值要看得见。 */
export function formatIdleFor(idleMillis: number): string {
  if (idleMillis < 60_000) return '刚刚用过';
  const minutes = Math.floor(idleMillis / 60_000);
  if (minutes < 60) return `闲置 ${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `闲置 ${hours} 小时`;
  return `闲置 ${Math.floor(hours / 24)} 天`;
}

/**
 * 关闭这个池会不会打断谁。
 *
 * 关池与改连接配置走同一条淘汰路径，但那时用户知道自己在改配置；手动关池得先说清楚
 * 有没有正在执行的语句会被一起掐掉。
 */
export function poolCloseWarning(pool: ConnectionPoolStatus): string | null {
  const busy = pool.active + pool.pendingBorrows;
  if (busy <= 0) return null;
  return `池里还有 ${busy} 条连接正在使用，关闭会中断它们正在执行的语句。`;
}
