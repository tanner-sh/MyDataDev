import type { AiGlossaryEntry, AiGlossarySuggestion } from './types';

/**
 * 把选中的候选词条并进现有词典。
 *
 * <p>纯逻辑单独放，是因为「重复」这件事有两种，两种都得挡：业务词本身重名（后端有唯一约束，
 * 撞了会整批保存失败），以及同一批候选被点了两次。前者只在保存时才报错，等到那时用户已经
 * 改了半天；在这里挡掉，用户根本碰不到那个错误。</p>
 */
export function mergeSuggestions(
  existing: AiGlossaryEntry[],
  picked: AiGlossarySuggestion[]
): AiGlossaryEntry[] {
  const taken = new Set(existing.map((entry) => normalize(entry.term)));
  const merged = [...existing];
  // 负数 id 表示「还没落库」，与界面上「添加业务词」用的是同一套约定。
  let nextId = -Date.now();
  for (const suggestion of picked) {
    const term = suggestion.term.trim();
    if (!term || taken.has(normalize(term))) continue;
    taken.add(normalize(term));
    merged.push({
      id: nextId,
      term,
      aliases: [...suggestion.aliases],
      objectNames: [...suggestion.objectNames],
      description: suggestion.description || ''
    });
    nextId -= 1;
  }
  return merged;
}

/** 候选里哪些已经在词典中了 —— 界面据此把它们标灰，而不是让用户点了才发现没反应。 */
export function alreadyInGlossary(
  existing: AiGlossaryEntry[],
  suggestions: AiGlossarySuggestion[]
): Set<string> {
  const taken = new Set(existing.map((entry) => normalize(entry.term)));
  return new Set(suggestions.filter((item) => taken.has(normalize(item.term))).map((item) => item.term));
}

function normalize(value: string): string {
  return value.trim().toLowerCase();
}
