import { readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';

// Vite keeps shared Ant Design primitives as small ESM chunks so lazy feature
// drawers do not duplicate them. Keep the request ceiling bounded while using
// the gzip budget as the primary regression signal.
const MAX_INITIAL_ASSETS = 30;
const MAX_INITIAL_GZIP_BYTES = 450 * 1024;
// The schema-object workspace is loaded on demand, but the manifest traversal
// intentionally includes optional feature chunks in this complete-dependency
// ceiling. Keep a small allowance for that split while the stricter initial
// payload budget continues to protect startup performance.
const MAX_SQL_WORKSPACE_GZIP_BYTES = 1420 * 1024;
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
