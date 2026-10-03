import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

function runFixture({ downloadFails = false, checksumFails = false, pullFails = false } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'dm-bootstrap-'));
  const log = join(dir, 'calls');
  const archive = join(dir, 'dm.tar');
  const executable = (name, body) => writeFileSync(join(dir, name), `#!/bin/bash\n${body}\n`, { mode: 0o755 });
  executable('curl', `printf 'partial-or-archive' > "$DM_TEST_ARCHIVE.part"; exit ${downloadFails ? 22 : 0}`);
  executable('sha256sum', `cat >/dev/null; exit ${checksumFails ? 1 : 0}`);
  executable('docker', `
printf '%s\\n' "$*" >> "$DM_CALL_LOG"
case "$1 $2" in
  'container inspect') exit 1 ;;
  'pull --platform') exit ${pullFails ? 1 : 0} ;;
  'logs mydatadev-dm-test') echo 'SYSTEM IS READY' ;;
esac
`);
  try {
    const result = spawnSync('bash', ['scripts/start-dameng-test.sh'], {
      encoding: 'utf8', timeout: 5000,
      env: { ...process.env, PATH: `${dir}:${process.env.PATH}`, DM_TEST_ARCHIVE: archive,
        DM_TEST_CONTAINER: 'mydatadev-dm-test', DM_CALL_LOG: log }
    });
    return { ...result, calls: readFileSync(log, 'utf8'), partialExists: existsSync(`${archive}.part`) };
  } finally { rmSync(dir, { recursive: true, force: true }); }
}

test('HTTP failure falls back to a pinned amd64 image and removes the partial archive', () => {
  const result = runFixture({ downloadFails: true });
  assert.equal(result.status, 0, result.stderr);
  const image = 'docker.io/greyhawk/dm8_single@sha256:9a2d9aecc31372c8f7c765625f36987a54ce69aa015eb3d856cfa1e29b06811b';
  assert.ok(result.calls.includes(`pull --platform linux/amd64 ${image}`));
  assert.ok(result.calls.split('\n').some(line => line.startsWith('run ') && line.endsWith(image)));
  assert.equal(result.partialExists, false);
  assert.ok(!result.calls.includes('load '));
});

test('successful official download loads the verified archive without registry access', () => {
  const result = runFixture();
  assert.equal(result.status, 0, result.stderr);
  assert.ok(result.calls.includes('load -i '));
  assert.ok(!result.calls.includes('pull '));
});

test('checksum mismatch fails closed without pulling or starting a container', () => {
  const result = runFixture({ checksumFails: true });
  assert.notEqual(result.status, 0);
  assert.ok(!/\b(pull|load|run) /.test(result.calls));
});

test('unavailable registry fails instead of starting an unverified local tag', () => {
  const result = runFixture({ downloadFails: true, pullFails: true });
  assert.notEqual(result.status, 0);
  assert.ok(!result.calls.includes('run '));
});
