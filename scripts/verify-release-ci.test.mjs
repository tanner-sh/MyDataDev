import test from 'node:test';
import assert from 'node:assert/strict';
import { verifyReleaseCi, REQUIRED_JOBS } from './verify-release-ci.mjs';

const sha = 'a'.repeat(40);
const run = { id: 10, run_attempt: 2, head_sha: sha, head_branch: 'main', event: 'push',
  status: 'completed', conclusion: 'success', html_url: 'https://example.test/run/10' };
function options({ status = 'identical', runs = [run], jobs = REQUIRED_JOBS.map(name => ({ name, conclusion: 'success' })),
  responseStatus = 200 } = {}) {
  return { repository: 'owner/repo', sha, token: 'test-token', log() {},
    fetchImpl: async url => ({ ok: responseStatus === 200, status: responseStatus,
      json: async () => url.includes('/compare/') ? { status, merge_base_commit: { sha } }
        : url.includes('/jobs?') ? { jobs } : { workflow_runs: runs } }) };
}
test('只接受同一个 main 提交的完整成功检查', async () => {
  assert.equal(await verifyReleaseCi(options()), 10);
  assert.equal(await verifyReleaseCi(options({ status: 'ahead' })), 10);
});
test('拒绝不属于 main 的 tag', async () => {
  await assert.rejects(verifyReleaseCi(options({ status: 'diverged' })), /不属于 main/);
});
test('不允许用别的 SHA、PR 或其他分支的成功记录代替', async () => {
  for (const changed of [{ head_sha: 'b'.repeat(40) }, { head_branch: 'feature' }, { event: 'pull_request' }]) {
    await assert.rejects(verifyReleaseCi(options({ runs: [{ ...run, ...changed }] })), /没有 main/);
  }
});
test('最新失败或取消不能被更早的成功掩盖', async () => {
  for (const conclusion of ['failure', 'cancelled', 'skipped', 'timed_out']) {
    await assert.rejects(verifyReleaseCi(options({ runs: [run, { ...run, id: 11, conclusion }] })), /最新 CI 未通过/);
  }
});
test('工作流整体成功也不能漏掉或跳过必需任务', async () => {
  await assert.rejects(verifyReleaseCi(options({ jobs: [] })), /必需作业/);
  await assert.rejects(verifyReleaseCi(options({ jobs: REQUIRED_JOBS.map(name => ({ name, conclusion: 'skipped' })) })), /必需作业/);
});
test('等待正在运行的 CI，超过上限仍不发布', async () => {
  let time = 0;
  await assert.rejects(verifyReleaseCi({ ...options({ runs: [{ ...run, status: 'in_progress' }] }),
    now: () => time, sleep: async ms => { time += ms; }, timeoutMs: 1 }), /超时/);
});
test('API 错误必须失败，不能视为没有失败记录', async () => {
  await assert.rejects(verifyReleaseCi(options({ responseStatus: 403 })), /API 请求失败/);
});
