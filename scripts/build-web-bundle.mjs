// 构建 Web 发行包：
//   release-assets/MyDataDev-<version>-web.jar             内置前端的可执行 JAR
//   release-assets/MyDataDev-<version>-frontend-dist.tar.gz 供 Nginx 等独立部署的前端静态资源
//
// 用法：node scripts/build-web-bundle.mjs
import { access, cp, mkdir, readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const projectDirectory = path.resolve(directory, '..');
const frontendDirectory = path.join(projectDirectory, 'frontend');
const backendDirectory = path.join(projectDirectory, 'backend');
const desktopDirectory = path.join(projectDirectory, 'desktop');
const releaseDirectory = path.join(projectDirectory, 'release-assets');
const stagingDirectory = path.join(projectDirectory, 'build', 'web-bundle');

function command(name) {
  return process.platform === 'win32' ? `${name}.cmd` : name;
}

function run(executable, args, cwd) {
  const shell = process.platform === 'win32' && executable.toLowerCase().endsWith('.cmd');
  const result = spawnSync(executable, args, { cwd, stdio: 'inherit', env: process.env, shell });
  if (result.status !== 0) process.exit(result.status || 1);
}

// 三个模块版本号必须一致，与桌面打包共用同一套校验。
run(process.execPath, [path.join(desktopDirectory, 'scripts', 'check-versions.mjs')], desktopDirectory);
const { version } = JSON.parse(await readFile(path.join(desktopDirectory, 'package.json'), 'utf8'));

run(command('npm'), ['run', 'build'], frontendDirectory);
// clean 掉上一次构建，避免过期的指纹资源留在 JAR 的 static/ 里。
run(command('mvn'), ['-Pweb', '-DskipTests', 'clean', 'package'], backendDirectory);

const packagedIndex = path.join(backendDirectory, 'target', 'classes', 'static', 'index.html');
try {
  await access(packagedIndex);
} catch {
  throw new Error('JAR 中缺少 static/index.html，前端未被打包进 Web 发行包。');
}

await rm(releaseDirectory, { recursive: true, force: true });
await rm(stagingDirectory, { recursive: true, force: true });
await mkdir(releaseDirectory, { recursive: true });

const jarName = `MyDataDev-${version}-web.jar`;
await cp(path.join(backendDirectory, 'target', 'mydatadev-web.jar'), path.join(releaseDirectory, jarName));
console.log(jarName);

const distName = `MyDataDev-${version}-frontend`;
const distStaging = path.join(stagingDirectory, distName);
await cp(path.join(frontendDirectory, 'dist'), distStaging, { recursive: true });
// .vite/manifest.json 只服务于构建预算校验，不需要发给部署方。
await rm(path.join(distStaging, '.vite'), { recursive: true, force: true });
const archiveName = `${distName}-dist.tar.gz`;
run('tar', ['-czf', path.join(releaseDirectory, archiveName), '-C', stagingDirectory, distName], projectDirectory);
await rm(stagingDirectory, { recursive: true, force: true });
console.log(archiveName);
