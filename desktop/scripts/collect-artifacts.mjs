import { cp, mkdir, readFile, readdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const desktopDirectory = path.resolve(directory, '..');
const outputDirectory = path.join(desktopDirectory, 'out', 'make');
const releaseDirectory = path.join(desktopDirectory, 'release-assets');
const packageJson = JSON.parse(await readFile(path.join(desktopDirectory, 'package.json'), 'utf8'));
const platform = process.env.MYDATADEV_RELEASE_PLATFORM || process.platform;
const arch = process.env.MYDATADEV_RELEASE_ARCH || process.arch;

async function filesUnder(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const fullPath = path.join(root, entry.name);
    if (entry.isDirectory()) result.push(...await filesUnder(fullPath));
    else result.push(fullPath);
  }
  return result;
}

const rules = platform === 'darwin'
  ? [{ extension: '.dmg', name: `MyDataDev-${packageJson.version}-macos-${arch}.dmg` }]
  : platform === 'win32'
    ? [{ extension: '.exe', name: `MyDataDev-${packageJson.version}-windows-${arch}-Setup.exe` }]
    : [
        { extension: '.deb', name: `MyDataDev-${packageJson.version}-linux-${arch}.deb` },
        { extension: '.rpm', name: `MyDataDev-${packageJson.version}-linux-${arch}.rpm` }
      ];

const outputFiles = await filesUnder(outputDirectory);
await rm(releaseDirectory, { recursive: true, force: true });
await mkdir(releaseDirectory, { recursive: true });

for (const rule of rules) {
  const candidates = outputFiles.filter((file) => file.toLowerCase().endsWith(rule.extension));
  if (candidates.length !== 1) {
    throw new Error(`期望找到 1 个 ${rule.extension} 安装包，实际找到 ${candidates.length} 个。`);
  }
  await cp(candidates[0], path.join(releaseDirectory, rule.name));
  console.log(rule.name);
}
