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
const MAX_SQL_WORKSPACE_GZIP_BYTES = 784 * 1024;
// 编辑器从 Monaco 换成 CodeMirror 6 之后是 128 KiB（此前 685 KiB）。
// 上限贴着实际值留一点余量：这块曾经是全站最大的资源，退回去不该悄无声息。
const MAX_SQL_EDITOR_GZIP_BYTES = 160 * 1024;
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

console.log(`构建预算：${summary}`);

const failures = [];
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
if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
}

function formatBytes(bytes) {
  return `${(bytes / 1024).toFixed(1)} KiB`;
}

function collectManifestFiles(key, files, visited = new Set()) {
  if (visited.has(key)) return;
  visited.add(key);
  const entry = manifest[key];
  if (!entry) return;
  if (entry.file) files.add(entry.file);
  for (const file of [...(entry.css || []), ...(entry.assets || [])]) files.add(file);
  for (const dependency of [...(entry.imports || []), ...(entry.dynamicImports || [])]) {
    collectManifestFiles(dependency, files, visited);
  }
}
