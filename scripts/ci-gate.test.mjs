import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { REQUIRED_JOBS } from './verify-release-ci.mjs';

// 仓库里没有 YAML 解析库，也不值得为这一个测试引一个：ci.yml 的作业与 needs 都按固定缩进书写，按行读就够了。
const lines = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8').split('\n');
const JOB = /^  ([\w-]+):\s*$/;
const jobs = lines.slice(lines.indexOf('jobs:') + 1).map(line => JOB.exec(line)?.[1]).filter(Boolean);

function jobBlock(id) {
  const start = lines.indexOf(`  ${id}:`);
  const end = lines.findIndex((line, index) => index > start && JOB.test(line));
  return lines.slice(start + 1, end === -1 ? undefined : end);
}

const gate = jobBlock('gate');
const needsAt = gate.indexOf('    needs:');
const needs = [];
for (const line of gate.slice(needsAt + 1)) {
  const match = /^      - ([\w-]+)\s*$/.exec(line);
  if (!match) break;
  needs.push(match[1]);
}

test('Gate 依赖 ci.yml 里的全部其他作业', () => {
  assert.ok(jobs.includes('gate'), 'ci.yml 里没有 gate 作业');
  assert.ok(needsAt !== -1 && needs.length > 0, 'gate 的 needs 需按块列表逐行书写');
  assert.deepEqual([...needs].sort(), jobs.filter(id => id !== 'gate').sort(),
    '新增或改名作业后要同步 gate 的 needs，否则该作业不受分支保护与发布检查约束');
});

test('分支保护与发布只认 Gate，且整轮取消时 Gate 仍会运行', () => {
  const name = gate.map(line => /^    name: (.+)$/.exec(line)?.[1]).find(Boolean);
  assert.deepEqual(REQUIRED_JOBS, [name]);
  assert.ok(gate.includes('    if: always()'));
});
