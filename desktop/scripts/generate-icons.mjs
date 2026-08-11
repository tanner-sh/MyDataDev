import { mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pngToIco from 'png-to-ico';
import sharp from 'sharp';
import { spawnSync } from 'node:child_process';

const directory = path.dirname(fileURLToPath(import.meta.url));
const desktopDirectory = path.resolve(directory, '..');
const source = path.resolve(desktopDirectory, '..', 'frontend', 'public', 'favicon.svg');
const assets = path.join(desktopDirectory, 'assets');
const iconset = path.join(assets, 'MyDataDev.iconset');

await mkdir(assets, { recursive: true });
await sharp(source).resize(1024, 1024).png().toFile(path.join(assets, 'icon.png'));

const icoSizes = [16, 24, 32, 48, 64, 128, 256].map(async (size) =>
  await sharp(source).resize(size, size).png().toBuffer()
);
await writeFile(path.join(assets, 'icon.ico'), await pngToIco(await Promise.all(icoSizes)));

if (process.platform === 'darwin') {
  await rm(iconset, { recursive: true, force: true });
  await mkdir(iconset, { recursive: true });
  for (const size of [16, 32, 128, 256, 512]) {
    await sharp(source).resize(size, size).png().toFile(path.join(iconset, `icon_${size}x${size}.png`));
    await sharp(source).resize(size * 2, size * 2).png().toFile(path.join(iconset, `icon_${size}x${size}@2x.png`));
  }
  const result = spawnSync('iconutil', ['-c', 'icns', iconset, '-o', path.join(assets, 'icon.icns')], { stdio: 'inherit' });
  if (result.status !== 0) process.exit(result.status || 1);
  await rm(iconset, { recursive: true, force: true });
}
