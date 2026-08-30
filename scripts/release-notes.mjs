/**
 * 拼出一个版本的 GitHub Release 说明。
 *
 * 版本相关的内容只在 CHANGELOG.md 维护一份，这里按版本号把对应那节抽出来，再接上
 * docs/release-notes.md 里跨版本不变的部分（发行包说明、快速启动、macOS 首次启动）。
 *
 * 之前 docs/release-notes.md 里写死了一节「x.y.z 更新说明」，没人记得改 —— 0.5.0 发出去时
 * 还挂着 0.4.0 的内容。抽取失败会直接退出非零，让发布流水线停下来，而不是发出一份错的说明。
 *
 * 用法：node scripts/release-notes.mjs v0.5.0 > notes.md
 */
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const requested = process.argv[2];
if (!requested) {
  console.error('用法：node scripts/release-notes.mjs <版本号，如 v0.5.0>');
  process.exit(1);
}
const version = requested.replace(/^v/, '');

const changelog = await readFile(path.join(projectDirectory, 'CHANGELOG.md'), 'utf8');
const heading = new RegExp(`^## \\[${version.replace(/\./g, '\\.')}\\].*$`, 'm');
const start = changelog.search(heading);
if (start < 0) {
  console.error(`CHANGELOG.md 里没有 ${version} 这一节。发版前请先补上。`);
  process.exit(1);
}
const rest = changelog.slice(start);
const nextHeading = rest.slice(1).search(/^## \[/m);
const section = (nextHeading < 0 ? rest : rest.slice(0, nextHeading + 1)).trimEnd();
// 去掉 CHANGELOG 里的日期后缀，Release 页面自己有发布时间。
const body = section.replace(/^## \[[^\]]+\][^\n]*/, `## ${version} 更新说明`);

const stable = await readFile(path.join(projectDirectory, 'docs', 'release-notes.md'), 'utf8');
process.stdout.write(`${body}\n\n${stable.replace(/^<!--[\s\S]*?-->\n+/, '')}`);
