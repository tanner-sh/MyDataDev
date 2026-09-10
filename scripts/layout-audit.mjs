/**
 * 布局不变量审计：在页面里量几何与计算样式，报告「布局坏了」的确凿证据。
 *
 * 为什么不是截图基线对比：本地 macOS 与 CI Linux 的字体渲染不同，逐像素比对会持续误报，
 * 而每次有意的界面改动都要重新接受一遍基线 —— 单人项目里这个成本会让人直接把它关掉。
 * 这里换个思路：不问「和上次长得一样吗」，只问「有没有客观坏掉」——
 * 文字被切掉、元素跑出容器、页面出现横向滚动条、看得见却点不到的按钮、字号低于中文可读下限、
 * 前景背景对比度不足、暗色模式下某块忘了上色。这些判定与渲染细节无关，结论是确定的，
 * 失败信息直接点到具体元素，不需要人去比对图片。
 *
 * 它不判断好不好看 —— 配色、留白、层级是否舒服仍然只能靠人看截图，那部分不该由脚本假装回答。
 *
 * 用法（Node 侧）：
 *     import { AUDIT_SOURCE, formatViolations } from './layout-audit.mjs';
 *     const violations = await page.evaluate(AUDIT_SOURCE);
 */

/**
 * 在页面里执行的审计源码。
 *
 * 整段是一个立即执行函数，返回 `{rule, target, detail}[]`，必须可被 `JSON.stringify` 序列化
 * —— CDP 的 `Runtime.evaluate` 只带得回纯值。
 *
 * 例外清单（`skip`）是这段代码里唯一需要长期维护的部分：每一条都要写清为什么这不是缺陷，
 * 否则它会慢慢变成「把报错关掉」的地方。
 */
export const AUDIT_SOURCE = `
(() => {
  const violations = [];
  // key 是这条违规「可归并的那部分」：同一对前景/背景色在几十个元素上命中，根因是同一个。
  // 基线文件按 rule + key 记账，所以 key 必须只由取值决定，不带元素身份 —— 否则界面一改
  // 版基线就失效了。不给 key 的规则（几何类）永远不可基线化：那种问题只可能是真的坏了。
  const add = (rule, element, detail, key = '') => violations.push({ rule, target: describe(element), detail, key });

  /** 给元素一个人能认出、且能在源码里搜到的名字。 */
  function describe(element) {
    if (!element || element === document.documentElement) return 'html';
    const classes = [...element.classList].filter((name) => !name.startsWith('ant-fade') && !name.startsWith('ant-motion'));
    const name = element.tagName.toLowerCase()
      + (element.id ? '#' + element.id : '')
      + classes.slice(0, 3).map((c) => '.' + c).join('');
    const text = (element.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 24);
    return text ? name + ' 「' + text + '」' : name;
  }

  /**
   * 元素真正露在屏幕上的那块矩形，全被裁掉时返回 null。
   *
   * getBoundingClientRect 给的是未裁剪的位置：表格滚动到一半时，滚出可视区的那些行照样
   * 报得出坐标，看起来正落在下面的分页栏上。所以要顺着每个会裁剪的祖先（overflow 不是
   * visible 的）依次求交，再和视口求交 —— 这样量到的才是用户真的看得到的那部分。
   */
  function clippedRect(element) {
    let rect = element.getBoundingClientRect();
    let left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom;
    for (let node = element.parentElement; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.overflowX === 'visible' && style.overflowY === 'visible') continue;
      const clip = node.getBoundingClientRect();
      if (style.overflowX !== 'visible') { left = Math.max(left, clip.left); right = Math.min(right, clip.right); }
      if (style.overflowY !== 'visible') { top = Math.max(top, clip.top); bottom = Math.min(bottom, clip.bottom); }
    }
    left = Math.max(left, 0); top = Math.max(top, 0);
    right = Math.min(right, window.innerWidth); bottom = Math.min(bottom, window.innerHeight);
    if (right - left < 1 || bottom - top < 1) return null;
    return { left, top, right, bottom, width: right - left, height: bottom - top };
  }

  /**
   * 只有真的看得见的元素才值得断言；隐藏的东西量出来的几何是没有意义的。
   *
   * opacity 必须顺着祖先链看：opacity 不是继承属性，父容器 opacity: 0 时子元素自己的计算值
   * 仍然是 1。资源树的行内操作按钮就装在这样一个容器里（悬停才浮现），只看元素自身的话，
   * 每一行都会贡献两个「看得见却点不到」的假阳性 —— 它们压根就没显示出来。
   */
  function visible(element) {
    for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.display === 'none' || style.visibility !== 'visible') return false;
      if (Number(style.opacity) < 0.95) return false;
    }
    const rect = element.getBoundingClientRect();
    if (rect.width < 1 || rect.height < 1) return false;
    return clippedRect(element) !== null;
  }

  /** 顺着祖先链判断这个元素现在到底接不接受点击。 */
  function interactive(element) {
    for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
      if (getComputedStyle(node).pointerEvents === 'none') return false;
    }
    return true;
  }

  /**
   * 例外清单。每一条都是「这不是缺陷」，不是「这条太吵」。
   */
  function skip(element) {
    // 禁用态本来就该是浅的，拿 4.5:1 去要求它等于要求它看起来没被禁用。
    if (element.closest('[disabled],[aria-disabled="true"],.ant-btn-disabled,.ant-select-disabled,.ant-input-disabled,.ant-picker-disabled')) return true;
    // 占位符与水印是有意的次要文本，antd 自己的取值。
    if (element.closest('.ant-select-selection-placeholder,.ant-input::placeholder,.ant-empty,.ant-watermark')) return true;
    // 半透明遮罩之上量不出稳定的有效背景色。
    if (element.closest('.ant-drawer-mask,.ant-modal-mask,.ant-tooltip,.ant-popover')) return true;
    // 图标字体与图形没有文字可读性一说。
    if (element.closest('.anticon,svg,canvas')) return true;
    // 视觉隐藏的原生控件（文件选择框等）由旁边那个按钮代点，1×1 的它本来就不该被点到。
    if (element.closest('.visually-hidden,[hidden],[aria-hidden="true"]')) return true;
    return false;
  }

  /** 自己直接拥有文字的元素 —— 父容器的 textContent 会把整棵子树的字都算进来。 */
  function ownText(element) {
    let text = '';
    for (const node of element.childNodes) if (node.nodeType === 3) text += node.nodeValue;
    return text.trim();
  }

  function parseColor(value) {
    const match = /rgba?\\(([^)]+)\\)/.exec(value || '');
    if (!match) return null;
    const parts = match[1].split(/[,\\s/]+/).filter(Boolean).map(Number);
    if (parts.length < 3 || parts.some(Number.isNaN)) return null;
    return { r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1 };
  }

  function blend(front, back) {
    return {
      r: front.r * front.a + back.r * (1 - front.a),
      g: front.g * front.a + back.g * (1 - front.a),
      b: front.b * front.a + back.b * (1 - front.a),
      a: 1
    };
  }

  function luminance({ r, g, b }) {
    const channel = (value) => {
      const scaled = value / 255;
      return scaled <= 0.03928 ? scaled / 12.92 : Math.pow((scaled + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
  }

  function contrast(front, back) {
    const a = luminance(front), b = luminance(back);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  /**
   * 往上找第一个不透明的背景色。
   *
   * 遇到渐变或背景图就放弃 —— 那种情况下没有单一背景色可比，硬算出来的数是假的。
   */
  function effectiveBackground(element) {
    let layers = [];
    for (let node = element; node && node !== document.documentElement.parentNode; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.backgroundImage && style.backgroundImage !== 'none') return null;
      const color = parseColor(style.backgroundColor);
      if (!color || color.a === 0) continue;
      layers.push(color);
      if (color.a === 1) {
        let result = layers.pop();
        while (layers.length) result = blend(layers.pop(), result);
        return result;
      }
    }
    return null;
  }

  const theme = document.querySelector('.app-shell')?.dataset.theme || 'light';
  const pageBackground = effectiveBackground(document.body) || { r: 255, g: 255, b: 255, a: 1 };
  const pageIsDark = luminance(pageBackground) < 0.5;

  // ── 1. 页面级横向滚动条 ────────────────────────────────────────────────
  // 主内容区自己可以横向滚，但整页不该 —— 那意味着有东西把外壳撑破了。
  const documentOverflow = document.documentElement.scrollWidth - window.innerWidth;
  if (documentOverflow > 1) {
    const widest = [...document.querySelectorAll('.app-shell *')]
      .filter(visible)
      .map((element) => ({ element, right: clippedRect(element)?.right ?? 0 }))
      .filter((entry) => entry.right > window.innerWidth + 1)
      .sort((left, right) => right.right - left.right)[0];
    add('页面出现横向滚动条', widest?.element || document.body,
      '整页比视口宽 ' + documentOverflow + 'px' + (widest ? '，最右的元素到 ' + Math.round(widest.right) + 'px' : ''));
  }

  const candidates = [...document.querySelectorAll('.app-shell *, .auth-page *')];

  for (const element of candidates) {
    if (!visible(element) || skip(element)) continue;
    const style = getComputedStyle(element);
    const text = ownText(element);

    // ── 2. 文字被切掉 ──────────────────────────────────────────────────
    // 有 ellipsis 的截断是设计好的（会显示「…」），没有的才是真的把字吃掉了。
    if (text.length >= 2 && style.textOverflow !== 'ellipsis') {
      const clipsX = ['hidden', 'clip'].includes(style.overflowX);
      const clipsY = ['hidden', 'clip'].includes(style.overflowY);
      if (clipsX && element.scrollWidth - element.clientWidth > 1) {
        add('文字被横向切掉', element, 'scrollWidth ' + element.scrollWidth + ' > clientWidth ' + element.clientWidth);
      }
      if (clipsY && element.scrollHeight - element.clientHeight > 1) {
        add('文字被纵向切掉', element, 'scrollHeight ' + element.scrollHeight + ' > clientHeight ' + element.clientHeight);
      }
    }

    // ── 3. 字号下限 ────────────────────────────────────────────────────
    // CLAUDE.md：中文在 11px 以下笔画会糊，--text-xs 就是下限。
    if (text.length >= 2) {
      const size = Number.parseFloat(style.fontSize);
      if (size > 0 && size < 11) add('字号低于 11px 下限', element, style.fontSize);
    }

    // ── 4. 对比度 ──────────────────────────────────────────────────────
    if (text.length >= 2) {
      const foreground = parseColor(style.color);
      const background = effectiveBackground(element);
      if (foreground && background && foreground.a > 0) {
        const size = Number.parseFloat(style.fontSize);
        const weight = Number(style.fontWeight) || (style.fontWeight === 'bold' ? 700 : 400);
        const large = size >= 24 || (size >= 18.66 && weight >= 700);
        const required = large ? 3 : 4.5;
        const ratio = contrast(blend(foreground, background), background);
        if (ratio < required) {
          const pair = style.color + ' 在 rgb(' + [background.r, background.g, background.b].map(Math.round).join(',') + ') 上';
          add('对比度不足', element, ratio.toFixed(2) + ':1（需 ≥' + required + '，' + pair + '）', pair);
        }
      }
    }

    // ── 5. 暗色模式下忘了上色 ──────────────────────────────────────────
    // 只给浅色定义了背景、暗色那套忘了覆盖时，界面上就是一块突然发白的区域。
    // 判据取得很松（亮度 > 0.85 且面积够大），避免把有意的高亮小块报进来。
    if (theme === 'dark' && pageIsDark) {
      const own = parseColor(style.backgroundColor);
      const rect = clippedRect(element);
      if (own && own.a > 0.9 && luminance(own) > 0.85 && rect && rect.width * rect.height > 4000) {
        add('暗色模式下背景仍是亮色', element, style.backgroundColor + '，' + Math.round(rect.width) + '×' + Math.round(rect.height));
      }
    }
  }

  // ── 6. 可点元素被挡住 ────────────────────────────────────────────────
  // 判据不是「两个可点区域叠在一起」—— 那是正常的层级：资源树的操作按钮本来就压在整行
  // 那个按钮之上，它在上层、点得到，报出来只是噪音。真正的缺陷是「看得见却点不到」，
  // 所以直接做命中测试：从元素自己的中心点打下去，落到的必须是它自己、它的子孙或祖先。
  // 落到一个不相干的元素身上，说明有东西盖在它上面，用户点到的不是他看到的那个。
  //
  // 只测最上面那一层：抽屉或弹窗打开时，遮罩后面那一层是有意变惰性的，把整个外壳的按钮
  // 报成「点不到」说的是遮罩在正常工作，不是界面坏了。
  const blockingMask = [...document.querySelectorAll('.ant-drawer-mask,.ant-modal-mask')].some(visible);
  const topLayer = blockingMask ? '.ant-drawer, .ant-modal' : '.app-shell, .auth-page';
  const clickable = [...document.querySelectorAll(topLayer)]
    .flatMap((root) => [...root.querySelectorAll('button, a[href], input, [role="button"], [role="tab"]')])
    .filter((element) => visible(element) && !skip(element) && interactive(element))
    // antd Select 的内部 input 由 .ant-select-selector 盖着转发点击，这是组件的实现方式。
    .filter((element) => !element.classList.contains('ant-select-input'));

  for (const element of clickable) {
    // 探的是「露出来那块」的中心，而不是元素自己的中心：被裁掉一半时，元素中心可能压根
    // 不在屏幕上，拿它去命中测试问的是一个用户点不到的位置。
    const rect = clippedRect(element);
    if (!rect) continue;
    const x = Math.round(rect.left + rect.width / 2);
    const y = Math.round(rect.top + rect.height / 2);
    const hit = document.elementFromPoint(x, y);
    if (!hit) continue;
    if (hit === element || element.contains(hit) || hit.contains(element)) continue;
    const box = (target) => {
      const r = target.getBoundingClientRect();
      return [r.left, r.top, r.width, r.height].map(Math.round).join(',');
    };
    add('可点元素被挡住点不到', element,
      describe(hit) + ' 盖在中心点 (' + x + ',' + y + ') 上；自己 [' + box(element) + ']，遮挡者 [' + box(hit) + ']');
  }

  return violations;
})()
`;

/**
 * 按基线筛掉「已经知道、已经决定暂不处理」的违规。
 *
 * 为什么需要它：对比度这条规则一上线就在既有界面上命中一百多次，根因收敛到十几对颜色 ——
 * 那是调色板层面的决定（改主色、改标签色板），不是一次顺手的修复。把这些取值显式记在
 * `layout-baseline.json` 里，比把整条规则关掉好得多：清单一屏看得完、能 grep、能逐条销账，
 * 而任何**新出现**的颜色对照样让 CI 失败。
 *
 * 只有带 `key` 的规则可以进基线（颜色、字号这类由取值决定的）。几何类违规没有 key，
 * 永远进不了基线 —— 「文字被切掉」「按钮点不到」不存在「已知且可接受」这种状态。
 */
export function applyBaseline(violations, baseline) {
  const accepted = baseline?.accepted || {};
  return violations.filter((violation) => {
    if (!violation.key) return true;
    return !(accepted[violation.rule] || {})[violation.key];
  });
}

/**
 * 把审计结果压成一行给 `check()` 用的说明。
 *
 * 同一条规则可能在一屏上命中很多次（一处样式问题会牵连一整列单元格），全打出来会把
 * 真正的信息埋掉，所以按规则归并、每条只举一个例子。
 */
export function formatViolations(violations) {
  const byRule = new Map();
  for (const violation of violations) {
    if (!byRule.has(violation.rule)) byRule.set(violation.rule, []);
    byRule.get(violation.rule).push(violation);
  }
  return [...byRule.entries()]
    .map(([rule, list]) => {
      const first = list[0];
      const more = list.length > 1 ? `（共 ${list.length} 处）` : '';
      return `${rule}${more}：${first.target} — ${first.detail}`;
    })
    .join(' ⏎ ');
}
