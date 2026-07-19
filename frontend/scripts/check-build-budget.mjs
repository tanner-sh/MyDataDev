import { readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';

// Vite keeps shared Ant Design primitives as small ESM chunks so lazy feature
// drawers do not duplicate them. Keep the request ceiling bounded while using
// the gzip budget as the primary regression signal.
const MAX_INITIAL_ASSETS = 30;
const MAX_INITIAL_GZIP_BYTES = 450 * 1024;
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
const summary = `${assets.length} 个首屏资源，gzip ${formatBytes(gzipBytes)}`;

console.log(`构建预算：${summary}`);

const failures = [];
if (assets.length > MAX_INITIAL_ASSETS) {
  failures.push(`首屏资源数 ${assets.length} 超过限制 ${MAX_INITIAL_ASSETS}`);
}
if (gzipBytes > MAX_INITIAL_GZIP_BYTES) {
  failures.push(`首屏 gzip ${formatBytes(gzipBytes)} 超过限制 ${formatBytes(MAX_INITIAL_GZIP_BYTES)}`);
}
if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
}

function formatBytes(bytes) {
  return `${(bytes / 1024).toFixed(1)} KiB`;
}
