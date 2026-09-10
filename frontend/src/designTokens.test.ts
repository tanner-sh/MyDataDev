import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

// 直接读文件而不是 `?raw`：vitest 下 CSS 走的是另一条管线，`styles.css?raw` 拿回来的是空串
// —— 断言会全部通过，而这条守卫也就等于没写。
const read = (name: string) => readFileSync(path.join(__dirname, name), 'utf8');
const styles = read('styles.css');
const appSource = read('App.tsx');

/**
 * 两套尺度必须同值。
 *
 * antd 有自己的 token（圆角、字号、控件高度），手写 CSS 有 styles.css 顶部那套令牌。两者
 * 一旦分叉，就会在同一个界面上互相覆盖 —— 那正是这个仓库当初出现六十多处 !important 的来源，
 * 而清理完之后除了 App.tsx 里的一句注释，没有任何东西守着它不再分叉。
 *
 * 这几条断言就是那句注释的可执行版本：改了 --radius-md 而忘了改 ConfigProvider（或者反过来）
 * 会在这里失败，而不是等到界面上出现两种圆角、有人再去加 !important 压过去。
 */
function token(name: string): string {
  // 只取 :root 那一段，避免把 :root[data-theme="dark"] 里的同名令牌读进来。
  const root = styles.slice(0, styles.indexOf(':root[data-theme="dark"]'));
  const match = new RegExp(`--${name}:\\s*([^;]+);`).exec(root);
  expect(match, `styles.css 的 :root 里没有 --${name}`).not.toBeNull();
  return match![1].trim();
}

function antdToken(name: string): string {
  const match = new RegExp(`${name}:\\s*([^,}]+)`).exec(appSource);
  expect(match, `App.tsx 的 ConfigProvider 里没有 ${name}`).not.toBeNull();
  return match![1].trim();
}

describe('antd 的 token 与 styles.css 的令牌保持同值', () => {
  it('圆角同值', () => {
    expect(`${antdToken('borderRadius')}px`).toBe(token('radius-md'));
  });

  it('字号同值', () => {
    expect(`${antdToken('fontSize')}px`).toBe(token('text-md'));
  });

  it('主色同值', () => {
    // ConfigProvider 只收一个主色种子（antd 按浅/暗算法各自派生），所以它必须等于浅色那套的
    // --primary。暗色主题的 --primary 是另一个值，由 styles.css 单独定义。
    expect(antdToken('colorPrimary').replace(/['"]/g, '')).toBe(token('primary'));
  });
});

describe('字号令牌不低于中文可读下限', () => {
  it('--text-xs 是 11px，且没有更小的字号令牌', () => {
    // 中文在 10px 下笔画会糊，而这是一个中文界面。浏览器回归里的布局审计会在真实渲染上
    // 复查一遍（连 antd 组件自己派生的字号一起），这里守的是令牌本身。
    expect(token('text-xs')).toBe('11px');
    for (const name of ['text-xs', 'text-sm', 'text-md', 'text-lg', 'text-xl']) {
      expect(Number.parseFloat(token(name)), `--${name} 低于 11px`).toBeGreaterThanOrEqual(11);
    }
  });
});
