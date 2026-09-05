/**
 * 启动时看一眼有没有新版本。
 *
 * <p>桌面端此前没有任何更新通道：装过之后除非用户自己去翻 Release 页，否则永远停在装那天的
 * 版本上。这里补的是最轻的一档 —— <b>只查、只提示，不下载也不安装</b>。自动更新要签名、要
 * 更新服务器，而 macOS 目前是未签名发行（见 docs/macos-unsigned-release-notes.md），静默替换
 * 一个未签名的应用既做不到也不该做。</p>
 *
 * <p>纯逻辑放在这里，网络与弹窗留给 main.ts：版本比较是这件事里唯一容易出错的部分
 * （{@code 0.10.0} 比 {@code 0.9.0} 新，字符串比较会说反），值得单独测。</p>
 */
export type ReleaseInfo = { version: string; url: string };

/** GitHub Release 的最小形状；其余字段一概不关心。 */
type GithubRelease = { tag_name?: string; html_url?: string; draft?: boolean; prerelease?: boolean };

export const RELEASES_URL = 'https://api.github.com/repos/tanner-sh/MyDataDev/releases/latest';
/** 查更新不该拖慢启动，也不该在网络不通时一直挂着。 */
export const UPDATE_CHECK_TIMEOUT_MS = 5_000;

/**
 * 比较两个版本号。
 *
 * <p>只认 {@code 主.次.修订} 这三段数字，后面的预发布标记一律忽略：发行版号就是这个形状
 * （三个模块的版本号还有一致性校验兜着），多认几种写法只会多几种猜错的可能。</p>
 *
 * @return 正数表示 left 更新，负数表示 right 更新，0 表示一样
 */
export function compareVersions(left: string, right: string): number {
  const leftParts = parseVersion(left);
  const rightParts = parseVersion(right);
  for (let index = 0; index < 3; index += 1) {
    if (leftParts[index] !== rightParts[index]) return leftParts[index] - rightParts[index];
  }
  return 0;
}

/** 从 Release 响应里挑出可用的那个版本；草稿与预发布不提示。 */
export function parseLatestRelease(payload: unknown): ReleaseInfo | null {
  const release = payload as GithubRelease | null;
  if (!release || typeof release !== 'object') return null;
  if (release.draft || release.prerelease) return null;
  const version = normalizeVersion(release.tag_name);
  if (!version) return null;
  return { version, url: typeof release.html_url === 'string' ? release.html_url : '' };
}

/**
 * 要不要提示。
 *
 * <p>版本号解析不出来时一律不提示 —— 与其在启动时弹一个「有新版本」而用户点开发现没有，
 * 不如什么都不做。开发模式下也不提示：本地跑的版本号本来就可能落后于已发布的 tag。</p>
 */
export function shouldNotify(current: string, latest: ReleaseInfo | null, isDevelopment = false): boolean {
  if (isDevelopment || !latest || !latest.url) return false;
  if (parseVersion(current).every((part) => part === 0) && !/\d/.test(current)) return false;
  return compareVersions(latest.version, current) > 0;
}

function normalizeVersion(tag: string | undefined): string | null {
  if (typeof tag !== 'string') return null;
  const trimmed = tag.trim().replace(/^v/i, '');
  return /^\d+(\.\d+)*/.test(trimmed) ? trimmed : null;
}

function parseVersion(value: string): [number, number, number] {
  const parts = String(value ?? '')
    .trim()
    .replace(/^v/i, '')
    .split(/[-+]/)[0]
    .split('.')
    .map((part) => Number.parseInt(part, 10));
  return [parts[0] || 0, parts[1] || 0, parts[2] || 0];
}
