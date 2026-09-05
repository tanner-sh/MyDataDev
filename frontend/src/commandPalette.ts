/**
 * 命令面板的检索与排序。
 *
 * 功能长到一定数量之后，「知道有这个功能」和「找得到它」是两件事：备份在管理抽屉里、
 * 历史在 SQL 工作台的工具条上、切换连接在页头 —— 每加一个入口，其余入口就更难被想起来。
 * 命令面板不是又一个入口，而是把已有入口的名字变成可搜索的。
 *
 * Ctrl/Cmd+P 已经属于对象搜索（搜库里的表），这里是 Ctrl/Cmd+K（搜应用能做的事）。
 */

export type PaletteCommand = {
  id: string;
  /** 面板里显示的中文名，就用界面上的说法，不要另起一个。 */
  title: string;
  section: string;
  /** 英文与别名：中文界面里用户照样会打 sql、backup、diff。 */
  keywords?: string;
  /** 快捷键提示，让面板同时充当快捷键的说明书。 */
  hint?: string;
  /**
   * 不可用的原因。不可用的命令仍然列出来 —— 「备份需要先选一条连接」比让它凭空消失
   * 更说得清楚，用户不会怀疑是自己记错了。
   */
  disabledReason?: string;
};

export type ScoredCommand = PaletteCommand & { score: number };

export const RECENT_COMMAND_LIMIT = 5;

/**
 * 一条命令与检索词的匹配分；不匹配返回 null。
 *
 * 前缀 > 包含 > 子序列，标题优先于关键词。子序列让「bk」也能找到「backup」，
 * 但它排在所有直接包含之后：真正打了完整词的人不该被模糊匹配挤下去。
 */
export function scoreCommand(command: PaletteCommand, query: string): number | null {
  const needle = query.trim().toLowerCase();
  if (!needle) return 0;
  const title = command.title.toLowerCase();
  const keywords = (command.keywords || '').toLowerCase();
  if (title.startsWith(needle)) return 100;
  if (title.includes(needle)) return 80;
  if (keywords.startsWith(needle)) return 70;
  if (keywords.includes(needle)) return 60;
  if (isSubsequence(needle, title)) return 40;
  if (isSubsequence(needle, keywords)) return 30;
  return null;
}

/**
 * 排序后的命令列表。
 *
 * 不可用的一律沉底：它们值得被看见，但不该占着第一条 —— 回车会落在第一条上。
 */
export function filterCommands(
  commands: PaletteCommand[],
  query: string,
  recentIds: string[] = []
): ScoredCommand[] {
  // 有检索词时按匹配质量排；没有检索词时保持声明顺序 —— 那份顺序本身就是分组，
  // 按标题长短重排只会把「导航 / SQL / 管理」打散成一锅。
  const ranked = query.trim().length > 0;
  const scored: (ScoredCommand & { order: number })[] = [];
  commands.forEach((command, order) => {
    const score = scoreCommand(command, query);
    if (score == null) return;
    // 最近用过的加一点分：空检索词时它就是「常用靠前」，有检索词时只作同分时的先后。
    const recentIndex = recentIds.indexOf(command.id);
    const bonus = recentIndex < 0 ? 0 : RECENT_COMMAND_LIMIT - recentIndex;
    scored.push({ ...command, score: score + bonus, order });
  });
  return scored
    .sort((left, right) => {
      const leftDisabled = left.disabledReason ? 1 : 0;
      const rightDisabled = right.disabledReason ? 1 : 0;
      if (leftDisabled !== rightDisabled) return leftDisabled - rightDisabled;
      if (right.score !== left.score) return right.score - left.score;
      if (ranked && left.title.length !== right.title.length) return left.title.length - right.title.length;
      return left.order - right.order;
    })
    .map(({ order, ...command }) => command);
}

/** 按分区分组，分区顺序跟着排序结果里第一次出现的位置走。 */
export function groupCommands(commands: ScoredCommand[]): { section: string; commands: ScoredCommand[] }[] {
  const groups: { section: string; commands: ScoredCommand[] }[] = [];
  for (const command of commands) {
    const existing = groups.find((group) => group.section === command.section);
    if (existing) existing.commands.push(command);
    else groups.push({ section: command.section, commands: [command] });
  }
  return groups;
}

/** 上下移动选中项，两端循环。不可用的命令跳过 —— 停在一个按不动的条目上没有意义。 */
export function moveCommandSelection(current: number, total: number, delta: number, commands: ScoredCommand[]): number {
  if (total <= 0) return -1;
  let index = current;
  for (let step = 0; step < total; step++) {
    index = index < 0 ? (delta > 0 ? 0 : total - 1) : (index + delta + total) % total;
    if (!commands[index]?.disabledReason) return index;
  }
  return -1;
}

/** 最近使用过的命令 id，最新的在前，去重并截断。 */
export function rememberCommand(recentIds: string[], id: string): string[] {
  return [id, ...recentIds.filter((item) => item !== id)].slice(0, RECENT_COMMAND_LIMIT);
}

function isSubsequence(needle: string, haystack: string): boolean {
  if (!needle) return true;
  let index = 0;
  for (const character of haystack) {
    if (character === needle[index]) index += 1;
    if (index === needle.length) return true;
  }
  return false;
}
