import { chmod, copyFile, mkdir, readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import esbuild from 'esbuild';

const directory = path.dirname(fileURLToPath(import.meta.url));
const cliDirectory = path.resolve(directory, '..');
const projectDirectory = path.resolve(cliDirectory, '..');
const distDirectory = path.join(cliDirectory, 'dist');
const pkg = JSON.parse(await readFile(path.join(cliDirectory, 'package.json'), 'utf8'));

await rm(distDirectory, { recursive: true, force: true });
await mkdir(distDirectory, { recursive: true });

await esbuild.build({
  entryPoints: [path.join(cliDirectory, 'src', 'index.ts')],
  outfile: path.join(distDirectory, 'mdd.js'),
  bundle: true,
  platform: 'node',
  target: 'node22',
  format: 'esm',
  banner: {
    js: '#!/usr/bin/env node\nimport { createRequire as __mddCreateRequire } from "node:module"; const require = __mddCreateRequire(import.meta.url);'
  },
  define: {
    __MDD_VERSION__: JSON.stringify(pkg.version)
  },
  sourcemap: false,
  minify: false
});

if (process.platform !== 'win32') {
  await chmod(path.join(distDirectory, 'mdd.js'), 0o755);
}

for (const legalFile of ['LICENSE', 'NOTICE']) {
  const source = path.join(projectDirectory, legalFile);
  const target = path.join(cliDirectory, legalFile);
  await copyFile(source, target);
}
