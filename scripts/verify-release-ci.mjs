import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const API = 'https://api.github.com';
// 分支保护与发布使用同一份必需检查清单，避免只更新其中一端。
export const REQUIRED_JOBS = JSON.parse(readFileSync(new URL('../.github/main-protection.json', import.meta.url), 'utf8'))
  .required_status_checks.checks.map(check => check.context);

export async function verifyReleaseCi({ repository, sha, token, fetchImpl = fetch,
  sleep = ms => new Promise(resolve => setTimeout(resolve, ms)), now = Date.now,
  timeoutMs = 35 * 60_000, log = console.log }) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository || '') || !/^[a-f0-9]{40}$/.test(sha || '')) {
    throw new Error('仓库名或提交 SHA 不合法。');
  }
  if (!token) throw new Error('缺少 GitHub API token。');
  async function get(path) {
    const response = await fetchImpl(`${API}/repos/${repository}/${path}`, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
      signal: AbortSignal.timeout(30_000)
    });
    if (!response.ok) throw new Error(`GitHub API 请求失败（${response.status}）：${path}`);
    return response.json();
  }
  // base 是待发布的提交，head 是 main：只有 main 包含它时才允许发布。
  const comparison = await get(`compare/${sha}...main`);
  if (!['ahead', 'identical'].includes(comparison.status) || comparison.merge_base_commit?.sha !== sha) {
    throw new Error('待发布提交不属于 main，拒绝发布。');
  }
  const deadline = now() + timeoutMs;
  while (true) {
    const runs = await get(`actions/workflows/ci.yml/runs?head_sha=${sha}&branch=main&per_page=100`);
    const latest = runs.workflow_runs.filter(run => run.head_sha === sha && run.head_branch === 'main'
      && ['push', 'workflow_dispatch'].includes(run.event))
      .sort((a, b) => b.id - a.id)[0];
    if (!latest) throw new Error('该提交没有 main 分支的 CI 记录；请先运行持续集成。');
    if (latest.status === 'completed') {
      if (latest.conclusion !== 'success') throw new Error(`该提交最新 CI 未通过：${latest.conclusion}（${latest.html_url}）。`);
      const jobs = [];
      for (let page = 1; ; page++) {
        const result = await get(`actions/runs/${latest.id}/jobs?filter=latest&per_page=100&page=${page}`);
        jobs.push(...result.jobs);
        if (result.jobs.length < 100) break;
      }
      const missing = REQUIRED_JOBS.filter(name => !jobs.some(job => job.name === name && job.conclusion === 'success'));
      if (missing.length) throw new Error(`CI 必需作业未全部成功：${missing.join('、')}`);
      log(`发布检查通过：${sha}，${latest.html_url}`);
      return latest.id;
    }
    if (now() >= deadline) throw new Error('等待 CI 完成超时，拒绝发布。');
    log(`等待同一提交的 CI：${latest.html_url}`);
    await sleep(15_000);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const sha = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  await verifyReleaseCi({ repository: process.env.GITHUB_REPOSITORY, sha,
    token: process.env.GH_TOKEN || process.env.GITHUB_TOKEN });
}
