import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const desktopDirectory = path.resolve(directory, '..');
const projectDirectory = path.resolve(desktopDirectory, '..');

const desktopPackage = JSON.parse(await readFile(path.join(desktopDirectory, 'package.json'), 'utf8'));
const frontendPackage = JSON.parse(await readFile(path.join(projectDirectory, 'frontend', 'package.json'), 'utf8'));
const pom = await readFile(path.join(projectDirectory, 'backend', 'pom.xml'), 'utf8');
const backendVersion = pom.match(/<artifactId>web-db-admin<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1];
const expected = desktopPackage.version;

const versions = {
  desktop: expected,
  frontend: frontendPackage.version,
  backend: backendVersion
};
for (const [component, version] of Object.entries(versions)) {
  if (version !== expected) {
    throw new Error(`${component} 版本 ${version || '<missing>'} 与桌面版本 ${expected} 不一致。`);
  }
}

const releaseVersion = process.env.MYDATADEV_RELEASE_VERSION?.replace(/^v/, '');
if (releaseVersion && releaseVersion !== expected) {
  throw new Error(`发布版本 ${releaseVersion} 与项目版本 ${expected} 不一致。`);
}

console.log(`MyDataDev version ${expected}`);
