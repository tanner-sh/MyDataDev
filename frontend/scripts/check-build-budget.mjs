import { readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';

// Vite keeps shared Ant Design primitives as small ESM chunks so lazy feature
// drawers do not duplicate them. Keep the request ceiling bounded while using
// the gzip budget as the primary regression signal.
// 每新增一个懒加载面板，Rollup 都可能重新划分共享块边界：命令面板这一次把 antd 的 Button
// 从 button 块里拆了出来，于是首屏「资源数」+1 而首屏体积只多了 1.3 KiB —— 同样的代码，换了
// 个文件。所以这个上限是请求数的护栏，真正盯着回归的是下面的 gzip 预算。
const MAX_INITIAL_ASSETS = 32;
const MAX_INITIAL_GZIP_BYTES = 450 * 1024;
// The schema-object workspace is loaded on demand, but the manifest traversal
// intentionally includes optional feature chunks in this complete-dependency
// ceiling. Keep a small allowance for that split while the stricter initial
// payload budget continues to protect startup performance.
// 两行补全、共享字段缓存与表头备注增加约 3 KiB；给这次功能增长预留 6 KiB，
// 首屏与编辑器的独立预算保持原值，继续防止引入大型依赖。
// 增量行更新、统一缓存预算、可取消导出与阶段计时增加约 3.5 KiB；首屏实测仍为 238.9 KiB。
// 给完整可选功能链增加 8 KiB 余量，并新增下面独立的可输入工作台预算。
const MAX_SQL_WORKSPACE_GZIP_BYTES = 798 * 1024;
// 编辑器从 Monaco 换成 CodeMirror 6 之后是 128 KiB（此前 685 KiB）。
// 上限贴着实际值留一点余量：这块曾经是全站最大的资源，退回去不该悄无声息。
const MAX_SQL_EDITOR_GZIP_BYTES = 160 * 1024;
// 真正进入可输入工作台的静态依赖链，区别于登录页首屏和包含可选面板的完整依赖。
const MAX_READY_WORKSPACE_GZIP_BYTES = 610 * 1024;
const distDirectory = resolve(process.cwd(), 'dist');
const html = readFileSync(resolve(distDirectory, 'index.html'), 'utf8');
const assetUrls = [...new Set([...html.matchAll(/(?:src|href)="(\/[^"?]+\.(?:js|css))"/g)].map((match) => match[1]))];
const assets = assetUrls.map((assetUrl) => {
  const file = resolve(distDirectory, assetUrl.slice(1));
  return {
    assetUrl,
    bytes: statSync(file).size,
    gzipBytes: gzipSync(readFileSync(file)).byteLength
  };
});
const gzipBytes = assets.reduce((total, asset) => total + asset.gzipBytes, 0);
const manifest = JSON.parse(readFileSync(resolve(distDirectory, '.vite/manifest.json'), 'utf8'));
const sqlWorkspaceEntry = Object.entries(manifest).find(([, entry]) => entry.src === 'src/components/SqlWorkspace.tsx');
const sqlEditorEntry = Object.entries(manifest).find(([, entry]) => entry.src === 'src/components/SqlEditor.tsx');
const appEntry = Object.entries(manifest).find(([, entry]) => entry.src === 'src/App.tsx');
const readyFiles = new Set(assetUrls.map((assetUrl) => assetUrl.slice(1)));
for (const entry of [appEntry, sqlWorkspaceEntry, sqlEditorEntry]) {
  if (entry) collectManifestFiles(entry[0], readyFiles, new Set(), false);
}
const readyGzipBytes = [...readyFiles].reduce((total, file) => total + gzipSync(readFileSync(resolve(distDirectory, file))).byteLength, 0);
const codeMirrorSetup = readFileSync(resolve(process.cwd(), 'src/codemirrorSetup.ts'), 'utf8');
let sqlEditorGzipBytes = 0;
const workspaceFiles = new Set(assetUrls.map((assetUrl) => assetUrl.slice(1)));
if (sqlWorkspaceEntry) collectManifestFiles(sqlWorkspaceEntry[0], workspaceFiles);
const workspaceAssets = [...workspaceFiles]
  .filter((file) => /\.(?:js|css|woff2?)$/.test(file))
  .map((file) => ({
    file,
    gzipBytes: gzipSync(readFileSync(resolve(distDirectory, file))).byteLength
  }));
const workspaceGzipBytes = workspaceAssets.reduce((total, asset) => total + asset.gzipBytes, 0);
const summary = `${assets.length} 个首屏资源，gzip ${formatBytes(gzipBytes)}；SQL 工作台完整依赖 gzip ${formatBytes(workspaceGzipBytes)}`;

console.log(`构建预算：${summary}；可输入工作台静态依赖 gzip ${formatBytes(readyGzipBytes)}`);

const failures = [];
if (!appEntry || readyGzipBytes > MAX_READY_WORKSPACE_GZIP_BYTES) {
  failures.push(`可输入工作台静态依赖缺失或超过限制：${formatBytes(readyGzipBytes)} / ${formatBytes(MAX_READY_WORKSPACE_GZIP_BYTES)}`);
}
if (assets.length > MAX_INITIAL_ASSETS) {
  failures.push(`首屏资源数 ${assets.length} 超过限制 ${MAX_INITIAL_ASSETS}`);
}
if (gzipBytes > MAX_INITIAL_GZIP_BYTES) {
  failures.push(`首屏 gzip ${formatBytes(gzipBytes)} 超过限制 ${formatBytes(MAX_INITIAL_GZIP_BYTES)}`);
}
if (!sqlWorkspaceEntry) {
  failures.push('构建清单中未找到 SQL 工作台入口，无法执行完整依赖预算');
}
if (!sqlEditorEntry) {
  failures.push('构建清单中未找到 SQL 编辑器入口，无法检查自动补全能力');
} else {
  const sqlEditorFile = resolve(distDirectory, sqlEditorEntry[1].file);
  const sqlEditorBundle = readFileSync(sqlEditorFile, 'utf8');
  sqlEditorGzipBytes = gzipSync(sqlEditorBundle).byteLength;
  // 补全弹窗是靠 @codemirror/autocomplete 提供的，而它整个是可以被摇掉的。
  // 这两个类名来自该包自身的样式，缺了就说明弹窗没打进产物。
  if (!sqlEditorBundle.includes('cm-tooltip-autocomplete') || !sqlEditorBundle.includes('cm-completionLabel')) {
    failures.push('SQL 编辑器产物缺少 CodeMirror 自动补全扩展，补全弹窗将不可用');
  }
  // 视图能力单独守住，避免只剩一个可以输入但没有工作台体验的裸 contenteditable。
  if (!sqlEditorBundle.includes('cm-activeLine')) {
    failures.push('SQL 编辑器产物缺少 CodeMirror 视图扩展');
  }
  // minify 后 Lezer 的符号名会消失，不能拿一个无关的 CSS 类冒充 SQL 语法检查。
  // 构建前已经完成 TypeScript 模块解析；这里再确认 SQL parser 和高亮扩展确实被装进配置。
  if (!codeMirrorSetup.includes("from '@codemirror/lang-sql'")
      || !codeMirrorSetup.includes('sql({ dialect: StandardSQL')
      || !codeMirrorSetup.includes('syntaxHighlighting(sqlHighlightStyle)')) {
    failures.push('SQL 编辑器配置缺少 CodeMirror SQL 解析或语法高亮扩展');
  }
  if (sqlEditorGzipBytes > MAX_SQL_EDITOR_GZIP_BYTES) {
    failures.push(`SQL 编辑器 gzip ${formatBytes(sqlEditorGzipBytes)} 超过限制 ${formatBytes(MAX_SQL_EDITOR_GZIP_BYTES)}`);
  }
}
if (workspaceGzipBytes > MAX_SQL_WORKSPACE_GZIP_BYTES) {
  failures.push(`SQL 工作台完整依赖 gzip ${formatBytes(workspaceGzipBytes)} 超过限制 ${formatBytes(MAX_SQL_WORKSPACE_GZIP_BYTES)}`);
}
// ── 两套尺度必须同值 ──────────────────────────────────────────────────────
//
// antd 有自己的 token（圆角、字号、主色），styles.css 顶部有另一套令牌。两者一旦分叉，
// 就会在同一个界面上互相覆盖 —— 那正是这个仓库当年出现六十多处 !important 的来源，而清理
// 完之后除了 App.tsx 里的一句注释，没有任何东西守着它不再分叉。
//
// 这段检查放在这里而不是写成 vitest 用例，是因为它要读 styles.css 的原文：vitest 下 CSS
// 走的是另一条管线，`styles.css?raw` 拿回来是空串 —— 断言会全部通过，那条守卫就等于没写。
// 而这个脚本本来就是「硬约束」的落脚处，用 Node 直接读文件，也不牵扯 tsc 的类型配置。
const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8');
// 只看 :root 那一段，别把 :root[data-theme="dark"] 里的同名令牌读进来。
const lightTokens = styles.slice(0, styles.indexOf(':root[data-theme="dark"]'));
const appSource = readFileSync(resolve(process.cwd(), 'src/App.tsx'), 'utf8');
const cssToken = (name) => new RegExp(`--${name}:\\s*([^;]+);`).exec(lightTokens)?.[1].trim();
const antdToken = (name) => new RegExp(`${name}:\\s*([^,}]+)`).exec(appSource)?.[1].trim().replace(/['"]/g, '');

for (const [antd, css, unit] of [['borderRadius', 'radius-md', 'px'], ['fontSize', 'text-md', 'px'], ['colorPrimary', 'primary', '']]) {
  const left = antdToken(antd);
  const right = cssToken(css);
  if (left === undefined || right === undefined) {
    failures.push(`尺度令牌对不上：App.tsx 的 ${antd} 或 styles.css 的 --${css} 找不到，检查是不是改了名`);
  } else if (left + unit !== right) {
    failures.push(`尺度令牌分叉：App.tsx 的 ${antd} 是 ${left}${unit}，styles.css 的 --${css} 是 ${right}`);
  }
}
// 中文在 10px 下笔画会糊，--text-xs 是下限。浏览器回归里的布局审计会在真实渲染上复查一遍
// （连 antd 组件自己派生的字号一起），这里守的是令牌本身。
for (const name of ['text-xs', 'text-sm', 'text-md', 'text-lg', 'text-xl']) {
  const value = Number.parseFloat(cssToken(name) ?? 'NaN');
  if (!(value >= 11)) failures.push(`字号令牌 --${name} 是 ${cssToken(name)}，低于 11px 这个中文可读下限`);
}

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
}

function formatBytes(bytes) {
  return `${(bytes / 1024).toFixed(1)} KiB`;
}

function collectManifestFiles(key, files, visited = new Set(), includeDynamic = true) {
  if (visited.has(key)) return;
  visited.add(key);
  const entry = manifest[key];
  if (!entry) return;
  if (entry.file) files.add(entry.file);
  for (const file of [...(entry.css || []), ...(entry.assets || [])]) files.add(file);
  for (const dependency of [...(entry.imports || []), ...(includeDynamic ? entry.dynamicImports || [] : [])]) {
    collectManifestFiles(dependency, files, visited, includeDynamic);
  }
}
