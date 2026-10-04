// 在真实页面内合成透明背景后测量报告涉及的可操作文字，不以截图像素代替颜色。
function measureWorkbenchContrast() {
  const parse = (value) => {
    const values = value.match(/[\d.]+/g)?.map(Number);
    return values?.length >= 3 ? [...values.slice(0, 3), values[3] ?? 1] : null;
  };
  const blend = (front, back) => front.slice(0, 3).map((v, i) => v * front[3] + back[i] * (1 - front[3]));
  const luminance = (color) => color.map(v => {
    const s = v / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  }).reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
  const background = (element) => {
    const layers = [];
    for (let node = element; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.backgroundImage !== 'none') return null;
      const color = parse(style.backgroundColor);
      if (!color || color[3] === 0) continue;
      layers.push(color);
      if (color[3] === 1) {
        let result = layers.pop().slice(0, 3);
        while (layers.length) result = blend(layers.pop(), result);
        return result;
      }
    }
    return null;
  };
  const samples = [];
  const add = (name, element) => {
    if (!element || element.closest('[disabled]') || !element.getClientRects().length) return;
    const style = getComputedStyle(element);
    const back = background(element);
    const front = parse(style.color);
    if (!back || !front) { samples.push({ name, ratio: null }); return; }
    const a = luminance(blend(front, back)), b = luminance(back);
    samples.push({ name, text: element.textContent.trim(), color: style.color, background: back,
      ratio: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) });
  };
  for (const button of document.querySelectorAll('.sql-workspace button.ant-btn-primary:not([disabled])')) {
    const label = [...button.children].find(node => node.tagName === 'SPAN'
      && !node.classList.contains('ant-btn-icon') && node.textContent.trim());
    if (label) add('执行按钮', label);
  }
  add('连接和 Schema', document.querySelector('.sql-workspace-title .ant-typography-secondary'));
  for (const text of document.querySelectorAll('.sql-result-empty-state .ant-typography-secondary')) add('无结果操作提示', text);
  for (const span of document.querySelectorAll('.cm-activeLine span')) {
    if (span.textContent.includes('contrast_probe')) add('活动行注释', span);
  }
  return { samples, failures: samples.filter(sample => sample.ratio === null || sample.ratio < 4.5) };
}

export const WORKBENCH_CONTRAST_SOURCE = `(${measureWorkbenchContrast.toString()})()`;
